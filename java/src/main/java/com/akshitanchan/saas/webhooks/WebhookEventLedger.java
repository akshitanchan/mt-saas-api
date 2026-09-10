package com.akshitanchan.saas.webhooks;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// the received row is inserted and committed on its own, before any handler code runs, so a
// handler failure afterwards can mark this exact row failed instead of losing it to the
// rollback of one shared transaction that also covers the handler's own work
@Component
public class WebhookEventLedger {

    private final WebhookEventRepository repository;
    private final TransactionTemplate transactionTemplate;

    public WebhookEventLedger(WebhookEventRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public WebhookEvent recordReceived(String provider, String eventId, String eventType, Map<String, Object> payload) {
        return transactionTemplate.execute(status -> {
            WebhookEvent event = new WebhookEvent(provider, eventId);
            event.setEventType(eventType);
            event.setPayload(payload);
            return repository.save(event);
        });
    }

    public void markFailed(UUID id, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookEvent event = repository.findById(id).orElseThrow();
            event.setStatus("failed");
            event.setError(error);
            event.setProcessedAt(null);
        });
    }
}
