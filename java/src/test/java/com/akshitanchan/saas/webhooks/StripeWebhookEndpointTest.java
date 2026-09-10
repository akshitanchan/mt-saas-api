package com.akshitanchan.saas.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.OrgRepository;
import com.akshitanchan.saas.orgs.Plan;
import com.akshitanchan.saas.orgs.SubscriptionStatus;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class StripeWebhookEndpointTest extends OrgScopedTestSupport {

    @Autowired
    private OrgRepository orgRepository;

    @Test
    void firstDeliveryOfAKnownEventUpdatesTheOrgAndReturnsOk() {
        String jwt = login(uniqueEmail("webhook-owner"));
        String orgId = createOrg(jwt, "acme");
        String eventId = "evt_" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(eventId, "customer.subscription.updated", "active", orgId, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "ok", "event_id", eventId));

        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(org.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.active);
        assertThat(org.getPlan()).isEqualTo(Plan.pro);
    }

    @Test
    void replayOfTheSameEventIsIgnoredAsDuplicate() {
        String jwt = login(uniqueEmail("webhook-owner"));
        String orgId = createOrg(jwt, "acme");
        String eventId = "evt_" + UUID.randomUUID();
        Map<String, Object> payload = subscriptionEvent(eventId, "customer.subscription.updated", "active", orgId, null, null);

        ResponseEntity<Map<String, Object>> first = post("/webhooks/stripe", null, payload);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> replay = post("/webhooks/stripe", null, payload);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(
                Map.of("status", "ignored", "reason", "duplicate", "event_id", eventId, "duplicate", true));
    }

    @Test
    void retryAfterAFailureSucceedsOnceThePayloadIsFixed() {
        String jwt = login(uniqueEmail("webhook-owner"));
        String orgId = createOrg(jwt, "acme");
        String eventId = "evt_" + UUID.randomUUID();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", "not-a-dict");
        Map<String, Object> badPayload = new LinkedHashMap<>();
        badPayload.put("id", eventId);
        badPayload.put("type", "customer.subscription.updated");
        badPayload.put("data", data);

        ResponseEntity<Map<String, Object>> failed = post("/webhooks/stripe", null, badPayload);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failed.getBody()).isEqualTo(Map.of("detail", "webhook_processing_failed"));

        // the same event id, retried in place, with a well-shaped payload now succeeds
        ResponseEntity<Map<String, Object>> retried = post("/webhooks/stripe", null,
                subscriptionEvent(eventId, "customer.subscription.updated", "active", orgId, null, null));
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).isEqualTo(Map.of("status", "ok", "event_id", eventId));
    }

    @Test
    void unknownCustomerIsIgnored() {
        String eventId = "evt_" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null,
                subscriptionEvent(eventId, "customer.subscription.updated", "active", null, "cus_" + UUID.randomUUID(), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                Map.of("status", "ignored", "reason", "unknown_customer", "event_id", eventId));
    }

    @Test
    void missingCustomerIsIgnored() {
        String eventId = "evt_" + UUID.randomUUID();

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", "sub_" + UUID.randomUUID());
        object.put("status", "active");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", object);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", "customer.subscription.updated");
        payload.put("data", data);

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null, payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                Map.of("status", "ignored", "reason", "missing_customer", "event_id", eventId));
    }

    @Test
    void unhandledEventTypeIsIgnored() {
        String eventId = "evt_" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", "some.unhandled.type");
        payload.put("data", Map.of("object", Map.of()));

        ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null, payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                Map.of("status", "ignored", "reason", "unhandled_type", "event_id", eventId));
    }

    @Test
    void malformedJsonBodyIsRejected() {
        ResponseEntity<Map<String, Object>> response = postRaw("not json at all".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "invalid json"));
    }

    @Test
    void missingIdOrTypeIsRejected() {
        Map<String, Object> missingId = new LinkedHashMap<>();
        missingId.put("type", "customer.subscription.updated");
        missingId.put("data", Map.of("object", Map.of()));
        ResponseEntity<Map<String, Object>> withoutId = post("/webhooks/stripe", null, missingId);
        assertThat(withoutId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutId.getBody()).isEqualTo(Map.of("detail", "invalid_stripe_event"));

        Map<String, Object> missingType = new LinkedHashMap<>();
        missingType.put("id", "evt_" + UUID.randomUUID());
        missingType.put("data", Map.of("object", Map.of()));
        ResponseEntity<Map<String, Object>> withoutType = post("/webhooks/stripe", null, missingType);
        assertThat(withoutType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutType.getBody()).isEqualTo(Map.of("detail", "invalid_stripe_event"));
    }

    private static Map<String, Object> subscriptionEvent(
            String eventId, String type, String status, String orgId, String customerId, String subscriptionId) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", subscriptionId != null ? subscriptionId : "sub_" + UUID.randomUUID());
        object.put("customer", customerId != null ? customerId : "cus_" + UUID.randomUUID());
        object.put("status", status);
        if (orgId != null) {
            object.put("metadata", Map.of("org_id", orgId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", object);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", type);
        payload.put("data", data);
        return payload;
    }

    private ResponseEntity<Map<String, Object>> postRaw(byte[] body, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signatureHeader != null) {
            headers.set("stripe-signature", signatureHeader);
        }
        return restTemplate.exchange("/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(body, headers), MAP_BODY);
    }
}
