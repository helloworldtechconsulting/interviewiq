package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.SessionEvent;
import com.interviewiq.session.domain.SessionEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {

    List<SessionEvent> findAllBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    long countBySessionIdAndEventType(UUID sessionId, SessionEventType eventType);

    List<SessionEvent> findAllBySessionIdAndEventTypeOrderByCreatedAtAsc(UUID sessionId, SessionEventType eventType);
}
