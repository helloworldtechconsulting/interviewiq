package com.interviewengine.webhook.infrastructure;

import com.interviewengine.webhook.domain.WebhookEvent;
import com.interviewengine.webhook.domain.WebhookProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    /** Deduplication check — UNIQUE constraint on (provider, idempotency_key). */
    boolean existsByProviderAndIdempotencyKey(WebhookProvider provider, String idempotencyKey);

    Optional<WebhookEvent> findByProviderAndIdempotencyKey(WebhookProvider provider, String idempotencyKey);

    /** Unprocessed events for the retry worker. */
    Page<WebhookEvent> findAllByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);

    /** Cleanup old processed events to bound table growth. */
    @Modifying
    @Query("DELETE FROM WebhookEvent e WHERE e.processed = true AND e.processedAt < :before")
    int deleteProcessedBefore(OffsetDateTime before);
}
