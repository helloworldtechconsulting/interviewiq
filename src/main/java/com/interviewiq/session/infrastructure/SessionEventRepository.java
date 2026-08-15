package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.SessionEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Access to the domain event log (INTIQ-98).
 *
 * @see SessionEvent
 */
public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {

    /**
     * Every span for one session, ordered so the staff console can assemble the
     * tree in a single pass — top-level subflows first, then siblings in
     * sequence.
     */
    @Query("""
           SELECT e FROM SessionEvent e
           WHERE e.sessionId = :sessionId
           ORDER BY e.startedAt ASC, e.sequence ASC
           """)
    List<SessionEvent> findTraceBySessionId(@Param("sessionId") UUID sessionId);

    List<SessionEvent> findAllByParentIdOrderBySequenceAsc(UUID parentId);

    /**
     * Claims a batch of spans whose payloads are past the 30-day retention
     * window, for stripping.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} per PRD §7.9 so that each worker pod
     * claims a distinct set of rows. Without it, every pod would strip the same
     * rows — harmless here, unlike double-charging a wallet, but it is the same
     * pattern and there is no reason to make this the one sweep that ignores it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(value = """
           SELECT * FROM session_events
           WHERE payloads_stripped_at IS NULL
             AND (request_jsonb IS NOT NULL OR response_jsonb IS NOT NULL)
             AND started_at < :cutoff
           ORDER BY started_at ASC
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<SessionEvent> claimExpiredPayloads(@Param("cutoff") OffsetDateTime cutoff,
                                            @Param("batchSize") int batchSize);

    /**
     * Strips the payloads of the claimed spans, leaving the skeleton — timings,
     * outcomes, decisions and economics — permanently intact.
     */
    @Modifying
    @Query(value = """
           UPDATE session_events
           SET request_jsonb = NULL,
               response_jsonb = NULL,
               payloads_stripped_at = now()
           WHERE id IN (:ids)
           """, nativeQuery = true)
    int stripPayloads(@Param("ids") List<UUID> ids);

    /** Total LLM spend for one session, in paise. Feeds the cost-per-interview KPI. */
    @Query("""
           SELECT COALESCE(SUM(e.costPaise), 0) FROM SessionEvent e
           WHERE e.sessionId = :sessionId
           """)
    long totalCostPaiseBySessionId(@Param("sessionId") UUID sessionId);
}
