package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.ProctoringEvent;
import com.interviewiq.session.domain.ProctoringEventType;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
