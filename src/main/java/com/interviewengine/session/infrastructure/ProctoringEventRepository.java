package com.interviewengine.session.infrastructure;

import com.interviewengine.session.domain.ProctoringEvent;
import com.interviewengine.session.domain.ProctoringEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ProctoringEventRepository extends JpaRepository<ProctoringEvent, UUID> {

    List<ProctoringEvent> findAllBySessionIdOrderByOccurredAtDesc(UUID sessionId);

    long countBySessionIdAndEventType(UUID sessionId, ProctoringEventType eventType);

    List<ProctoringEvent> findAllBySessionIdAndEventTypeOrderByOccurredAtAsc(UUID sessionId, ProctoringEventType eventType);

    /**
     * Every proctoring event for a session, oldest first (INTIQ-29).
     *
     * <p>Chronological rather than grouped by type on purpose: three tab
     * switches in the last two minutes reads very differently from three spread
     * across an hour, and only the ordering shows that.
     */
    List<ProctoringEvent> findAllBySessionIdOrderByOccurredAtAsc(UUID sessionId);

    /**
     * Duplicate guard for the REST replay path (PRD §11).
     *
     * <p>A browser that lost its WebSocket buffers events and re-posts them
     * when it reconnects, and it cannot know which of them the server already
     * received before the socket dropped. Without this check a flaky connection
     * would inflate a candidate's tab-switch count — a number a recruiter reads
     * as evidence about a person.
     *
     * <p>The triple is a sound identity here because {@code occurred_at} comes
     * from the browser at event time and is carried through the replay
     * unchanged: the same event always presents the same timestamp, and two
     * genuinely distinct events of the same type in the same millisecond are
     * not a thing the Page Visibility API produces.
     */
    boolean existsBySessionIdAndEventTypeAndOccurredAt(
            UUID sessionId, ProctoringEventType eventType, OffsetDateTime occurredAt);
}
