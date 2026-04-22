package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.SessionEvent;
import com.interviewiq.session.domain.SessionEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {

    /** Fetch all events for a session ordered by arrival time — evaluation pipeline and compliance export. */
    List<SessionEvent> findAllBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    /** Anti-cheat rollup: count events of a specific type for a session. */
    long countBySessionIdAndEventType(UUID sessionId, SessionEventType eventType);

    /** All events of a specific type for a session — used when first-occurrence timestamp is needed. */
    List<SessionEvent> findAllBySessionIdAndEventTypeOrderByCreatedAtAsc(UUID sessionId, SessionEventType eventType);
}
