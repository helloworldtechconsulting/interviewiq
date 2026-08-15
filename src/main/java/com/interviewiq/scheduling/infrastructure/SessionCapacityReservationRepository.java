package com.interviewiq.scheduling.infrastructure;

import com.interviewiq.scheduling.domain.SessionCapacityReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionCapacityReservationRepository
        extends JpaRepository<SessionCapacityReservation, UUID> {

    List<SessionCapacityReservation> findAllBySessionId(UUID sessionId);

    /** Used when a session is cancelled, expires or is rescheduled. */
    void deleteAllBySessionId(UUID sessionId);

    boolean existsBySessionId(UUID sessionId);
}
