package com.akshitanchan.saas.webhooks;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);
}
