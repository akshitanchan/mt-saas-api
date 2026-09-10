package com.akshitanchan.saas.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.OrgRepository;
import com.akshitanchan.saas.orgs.Plan;
import com.akshitanchan.saas.orgs.SubscriptionStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// covers the stripe subscription-status mapping and the invoice.paid / invoice.payment_failed
// handlers, none of which StripeWebhookEndpointTest exercises: it only ever posts an "active"
// customer.subscription.updated event
class StripeSubscriptionAndInvoiceEventsTest extends OrgScopedTestSupport {

    @Autowired
    private OrgRepository orgRepository;

    @Test
    void subscriptionTrialingSetsProPlanAndTrialingStatus() {
        String orgId = orgWithCustomerId("cus_" + UUID.randomUUID());
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(org.getStripeCustomerId(), "trialing"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.trialing);
        assertThat(updated.getPlan()).isEqualTo(Plan.pro);
    }

    @Test
    void subscriptionUnpaidSetsFreePlanAndUnpaidStatus() {
        String orgId = orgWithCustomerId("cus_" + UUID.randomUUID());
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(org.getStripeCustomerId(), "unpaid"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.unpaid);
        assertThat(updated.getPlan()).isEqualTo(Plan.free);
    }

    @Test
    void subscriptionIncompleteSetsFreePlanAndIncompleteStatus() {
        String orgId = orgWithCustomerId("cus_" + UUID.randomUUID());
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(org.getStripeCustomerId(), "incomplete"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.incomplete);
        assertThat(updated.getPlan()).isEqualTo(Plan.free);
    }

    @Test
    void subscriptionDeletedForcesCanceledAndFreeEvenFromAnActiveProSubscription() {
        String orgId = orgWithCustomerId("cus_" + UUID.randomUUID());
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        org.setSubscriptionStatus(SubscriptionStatus.active);
        org.setPlan(Plan.pro);
        orgRepository.save(org);

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(org.getStripeCustomerId(), "customer.subscription.deleted", "canceled"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.canceled);
        assertThat(updated.getPlan()).isEqualTo(Plan.free);
    }

    @Test
    void invoicePaidSetsActiveAndProAndIsIdempotent() {
        String orgId = orgWithCustomerId("cus_" + UUID.randomUUID());
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        String eventId = "evt_" + UUID.randomUUID();
        Map<String, Object> payload = invoiceEvent(eventId, "invoice.paid", org.getStripeCustomerId());

        ResponseEntity<Map<String, Object>> first = post("/webhooks/stripe", null, payload);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.active);
        assertThat(updated.getPlan()).isEqualTo(Plan.pro);

        ResponseEntity<Map<String, Object>> replay = post("/webhooks/stripe", null, payload);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("duplicate")).isEqualTo(true);
    }

    @Test
    void invoicePaymentFailedSetsPastDueAndProThenBlocksWrites() {
        String email = uniqueEmail("invoice-fail-owner");
        String jwt = login(email);
        String orgId = createOrg(jwt, "acme");
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        org.setStripeCustomerId("cus_" + UUID.randomUUID());
        orgRepository.save(org);

        String eventId = "evt_" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> response = post(
                "/webhooks/stripe", null, invoiceEvent(eventId, "invoice.payment_failed", org.getStripeCustomerId()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Org updated = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.past_due);
        assertThat(updated.getPlan()).isEqualTo(Plan.pro);

        ResponseEntity<Map<String, Object>> blocked = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", "x"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "billing_required"));
    }

    private String orgWithCustomerId(String customerId) {
        String jwt = login(uniqueEmail("webhook-status"));
        String orgId = createOrg(jwt, "acme");
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        org.setStripeCustomerId(customerId);
        orgRepository.save(org);
        return orgId;
    }

    private static Map<String, Object> subscriptionEvent(String customerId, String status) {
        return subscriptionEvent(customerId, "customer.subscription.updated", status);
    }

    private static Map<String, Object> subscriptionEvent(String customerId, String type, String status) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", "sub_" + UUID.randomUUID());
        object.put("customer", customerId);
        object.put("status", status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", object);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "evt_" + UUID.randomUUID());
        payload.put("type", type);
        payload.put("data", data);
        return payload;
    }

    private static Map<String, Object> invoiceEvent(String eventId, String type, String customerId) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", "in_" + UUID.randomUUID());
        object.put("customer", customerId);
        object.put("subscription", "sub_" + UUID.randomUUID());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", object);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", type);
        payload.put("data", data);
        return payload;
    }
}
