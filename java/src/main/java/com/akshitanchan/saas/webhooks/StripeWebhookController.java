package com.akshitanchan.saas.webhooks;

import com.akshitanchan.saas.config.AppProperties;
import com.akshitanchan.saas.ratelimit.RateLimiter;
import com.akshitanchan.saas.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// mirrors app/routes/webhooks.py:stripe_webhook end to end: raw-body signature check, ledger
// dedupe/retry, and the per-event-type org mutation. permitted without auth by SecurityConfig.
@RestController
@RequestMapping("/webhooks")
public class StripeWebhookController {

    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final StripeSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventLedger ledger;
    private final StripeWebhookProcessor processor;
    private final ObjectMapper objectMapper;

    public StripeWebhookController(
            RateLimiter rateLimiter,
            AppProperties properties,
            StripeSignatureVerifier signatureVerifier,
            WebhookEventRepository webhookEventRepository,
            WebhookEventLedger ledger,
            StripeWebhookProcessor processor,
            ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.ledger = ledger;
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, Object>> stripe(
            HttpServletRequest request,
            @RequestHeader(value = "stripe-signature", required = false) String signature) throws IOException {
        rateLimiter.check("webhooks:stripe", properties.rateLimit().webhooksPerMin());

        byte[] raw = request.getInputStream().readAllBytes();
        signatureVerifier.verify(raw, signature);

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid json");
        }

        String eventId = stringOrNull(payload.get("id"));
        String eventType = stringOrNull(payload.get("type"));
        if (eventId == null || eventId.isEmpty() || eventType == null || eventType.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_stripe_event");
        }

        Object dataRaw = payload.get("data");
        Map<?, ?> data = dataRaw instanceof Map<?, ?> m ? m : Map.of();
        Object dataObject = data.get("object");

        WebhookEvent existing = webhookEventRepository.findByProviderAndEventId("stripe", eventId).orElse(null);
        if (existing != null && ("processed".equals(existing.getStatus()) || "ignored".equals(existing.getStatus()))) {
            return ResponseEntity.ok(response("ignored", "duplicate", eventId, true));
        }
        if (existing == null) {
            existing = ledger.recordReceived("stripe", eventId, eventType, payload);
        }

        try {
            StripeWebhookProcessor.Outcome outcome = processor.process(existing.getId(), eventType, dataObject);
            return ResponseEntity.ok(response(outcome.status(), outcome.reason(), eventId, null));
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            ledger.markFailed(existing.getId(), error.length() > 1000 ? error.substring(0, 1000) : error);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "webhook_processing_failed");
        }
    }

    private static String stringOrNull(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static Map<String, Object> response(String status, String reason, String eventId, Boolean duplicate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("event_id", eventId);
        if (duplicate != null) {
            body.put("duplicate", duplicate);
        }
        return body;
    }
}
