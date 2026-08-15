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
}
