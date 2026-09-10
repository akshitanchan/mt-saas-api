package com.akshitanchan.saas.webhooks;

import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.OrgRepository;
import com.akshitanchan.saas.orgs.Plan;
import com.akshitanchan.saas.orgs.SubscriptionStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// mirrors the handler body of app/routes/webhooks.py:stripe_webhook, everything after the
// ledger row for this event id already exists: map the event to an org mutation, or ignore it
// with a reason, and stamp the ledger row processed/ignored. runs in its own transaction so a
// thrown exception rolls back only this event's side effects, leaving the ledger row exactly
// as the controller found it for the ledger.markFailed(...) call that follows.
@Component
public class StripeWebhookProcessor {

    private static final Set<String> SUBSCRIPTION_TYPES =
            Set.of("customer.subscription.updated", "customer.subscription.deleted");
    private static final Set<String> INVOICE_TYPES = Set.of("invoice.paid", "invoice.payment_failed");

    private final WebhookEventRepository webhookEventRepository;
    private final OrgRepository orgRepository;

    public StripeWebhookProcessor(WebhookEventRepository webhookEventRepository, OrgRepository orgRepository) {
        this.webhookEventRepository = webhookEventRepository;
        this.orgRepository = orgRepository;
    }

    @Transactional
    public Outcome process(UUID webhookEventId, String eventType, Object dataObject) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId).orElseThrow();

        boolean handledType = SUBSCRIPTION_TYPES.contains(eventType) || INVOICE_TYPES.contains(eventType);
        if (handledType && !(dataObject instanceof Map)) {
            throw new StripeEventShapeException("stripe event data.object must be an object");
        }

        if (SUBSCRIPTION_TYPES.contains(eventType)) {
            return handleSubscriptionEvent(event, eventType, (Map<?, ?>) dataObject);
        }
        if (INVOICE_TYPES.contains(eventType)) {
            return handleInvoiceEvent(event, eventType, (Map<?, ?>) dataObject);
        }

        markIgnored(event);
        return Outcome.ignored("unhandled_type");
    }

    private Outcome handleSubscriptionEvent(WebhookEvent event, String eventType, Map<?, ?> obj) {
        String customer = stringValue(obj.get("customer"));
        if (customer == null || customer.isEmpty()) {
            markIgnored(event);
            return Outcome.ignored("missing_customer");
        }

        String subscriptionId = stringValue(obj.get("id"));
        Org org = findOrg(customer, subscriptionId, obj.get("metadata"));
        if (org == null) {
            markIgnored(event);
            return Outcome.ignored("unknown_customer");
        }

        if ("customer.subscription.deleted".equals(eventType)) {
            org.setSubscriptionStatus(SubscriptionStatus.canceled);
            org.setPlan(Plan.free);
        } else {
            SubscriptionStatus status = mapStripeSubStatus(stringValue(obj.get("status")));
            org.setSubscriptionStatus(status);
            org.setPlan(planForStatus(status));
        }
        stampSubscriptionId(org, subscriptionId);

        markProcessed(event);
        return Outcome.ok();
    }

    private Outcome handleInvoiceEvent(WebhookEvent event, String eventType, Map<?, ?> obj) {
        String customer = stringValue(obj.get("customer"));
        if (customer == null || customer.isEmpty()) {
            markIgnored(event);
            return Outcome.ignored("missing_customer");
        }

        String subscriptionId = stringValue(obj.get("subscription"));
        Org org = findOrg(customer, subscriptionId, obj.get("metadata"));
        if (org == null) {
            markIgnored(event);
            return Outcome.ignored("unknown_customer");
        }

        stampSubscriptionId(org, subscriptionId);
        if ("invoice.paid".equals(eventType)) {
            org.setSubscriptionStatus(SubscriptionStatus.active);
        } else {
            org.setSubscriptionStatus(SubscriptionStatus.past_due);
        }
        org.setPlan(Plan.pro);

        markProcessed(event);
        return Outcome.ok();
    }

    private static void stampSubscriptionId(Org org, String subscriptionId) {
        if (subscriptionId != null && !subscriptionId.isEmpty()) {
            org.setStripeSubscriptionId(subscriptionId);
        }
    }

    private Org findOrg(String customerId, String subscriptionId, Object metadata) {
        if (customerId != null && !customerId.isEmpty()) {
            Org byCustomer = orgRepository.findByStripeCustomerId(customerId).orElse(null);
            if (byCustomer != null) {
                return byCustomer;
            }
        }
        if (subscriptionId != null && !subscriptionId.isEmpty()) {
            Org bySubscription = orgRepository.findByStripeSubscriptionId(subscriptionId).orElse(null);
            if (bySubscription != null) {
                return bySubscription;
            }
        }
        if (metadata instanceof Map<?, ?> metadataMap) {
            Object rawOrgId = metadataMap.get("org_id");
            if (rawOrgId != null && !rawOrgId.toString().isEmpty()) {
                UUID orgId;
                try {
                    orgId = UUID.fromString(rawOrgId.toString());
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return orgRepository.findById(orgId).orElse(null);
            }
        }
        return null;
    }

    private static void markProcessed(WebhookEvent event) {
        event.setStatus("processed");
        event.setProcessedAt(OffsetDateTime.now());
    }

    private static void markIgnored(WebhookEvent event) {
        event.setStatus("ignored");
        event.setProcessedAt(OffsetDateTime.now());
    }

    private static String stringValue(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static SubscriptionStatus mapStripeSubStatus(String raw) {
        if (raw == null) {
            return SubscriptionStatus.none;
        }
        return switch (raw) {
            case "active" -> SubscriptionStatus.active;
            case "trialing" -> SubscriptionStatus.trialing;
            case "past_due" -> SubscriptionStatus.past_due;
            case "unpaid" -> SubscriptionStatus.unpaid;
            case "canceled" -> SubscriptionStatus.canceled;
            case "incomplete" -> SubscriptionStatus.incomplete;
            default -> SubscriptionStatus.none;
        };
    }

    private static Plan planForStatus(SubscriptionStatus status) {
        return switch (status) {
            case active, trialing, past_due -> Plan.pro;
            default -> Plan.free;
        };
    }

    public record Outcome(String status, String reason) {

        static Outcome ok() {
            return new Outcome("ok", null);
        }

        static Outcome ignored(String reason) {
            return new Outcome("ignored", reason);
        }
    }
}
