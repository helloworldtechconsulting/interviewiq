package com.interviewiq.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface SchedulingRepository extends JpaRepository<AvailabilitySlot, UUID> {
    List<AvailabilitySlot> findByJobOpeningIdAndStartTimeAfterOrderByStartTime(
            UUID jobOpeningId,
            LocalDateTime startTime);

    @Query("SELECT a FROM AvailabilitySlot a WHERE a.jobOpeningId = ?1 AND a.bookedCount < a.maxInterviews ORDER BY a.startTime ASC")
    List<AvailabilitySlot> findAvailableSlots(UUID jobOpeningId);

    @Query("SELECT a FROM AvailabilitySlot a WHERE a.jobOpeningId = ?1 AND a.bookedCount < a.maxInterviews AND a.startTime >= ?2 ORDER BY a.startTime ASC")
    List<AvailabilitySlot> findAvailableSlotsAfter(UUID jobOpeningId, LocalDateTime from);

    List<AvailabilitySlot> findByJobOpeningIdOrderByStartTime(UUID jobOpeningId);
}
