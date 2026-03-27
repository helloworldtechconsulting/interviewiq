package com.interviewiq.scheduling;

import com.interviewiq.common.BadRequestException;
import com.interviewiq.common.ResourceNotFoundException;
import com.interviewiq.job.JobOpening;
import com.interviewiq.job.JobOpeningRepository;
import com.interviewiq.scheduling.dto.CreateSlotRequest;
import com.interviewiq.scheduling.dto.SlotResponse;
import com.interviewiq.session.InterviewSession;
import com.interviewiq.session.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulingService {

    private final SchedulingRepository schedulingRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final InterviewSessionRepository sessionRepository;

    @Transactional
    public List<SlotResponse> createSlots(UUID jobOpeningId, List<CreateSlotRequest> requests) {
        JobOpening jobOpening = jobOpeningRepository.findById(jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        List<AvailabilitySlot> slots = requests.stream()
                .map(request -> {
                    validateSlot(request);
                    return AvailabilitySlot.builder()
                            .jobOpeningId(jobOpeningId)
                            .startTime(request.startTime())
                            .endTime(request.endTime())
                            .maxInterviews(request.maxInterviews())
                            .bookedCount(0)
                            .build();
                })
                .collect(Collectors.toList());

        List<AvailabilitySlot> savedSlots = schedulingRepository.saveAll(slots);

        log.info("Created {} availability slots for job opening: {}", savedSlots.size(), jobOpeningId);

        return savedSlots.stream()
                .map(this::toSlotResponse)
                .collect(Collectors.toList());
    }

    public List<SlotResponse> getAvailableSlots(UUID jobOpeningId) {
        jobOpeningRepository.findById(jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        List<AvailabilitySlot> slots = schedulingRepository.findAvailableSlotsAfter(
                jobOpeningId,
                LocalDateTime.now()
        );

        log.info("Found {} available slots for job opening: {}", slots.size(), jobOpeningId);

        return slots.stream()
                .map(this::toSlotResponse)
                .collect(Collectors.toList());
    }

    public List<SlotResponse> getAllSlots(UUID jobOpeningId) {
        jobOpeningRepository.findById(jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        List<AvailabilitySlot> slots = schedulingRepository.findByJobOpeningIdOrderByStartTime(jobOpeningId);

        return slots.stream()
                .map(this::toSlotResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public SlotResponse bookSlot(UUID sessionId, UUID slotId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        AvailabilitySlot slot = schedulingRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        // Check if slot is in the past
        if (slot.getStartTime().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Cannot book slots in the past");
        }

        // Check if slot is full (using optimistic locking)
        if (slot.getBookedCount() >= slot.getMaxInterviews()) {
            throw new BadRequestException("This slot is fully booked");
        }

        // Increment booked count
        slot.setBookedCount(slot.getBookedCount() + 1);
        AvailabilitySlot updatedSlot = schedulingRepository.save(slot);

        // Update session with scheduled time
        session.setAvailabilitySlotId(slotId);
        session.setScheduledAt(slot.getStartTime());
        sessionRepository.save(session);

        log.info("Candidate {} booked slot {} for session {}", session.getCandidateId(), slotId, sessionId);

        return toSlotResponse(updatedSlot);
    }

    @Transactional
    public void cancelSlotBooking(UUID sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        if (session.getAvailabilitySlotId() == null) {
            throw new BadRequestException("This session does not have a booked slot");
        }

        AvailabilitySlot slot = schedulingRepository.findById(session.getAvailabilitySlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot not found"));

        // Decrement booked count
        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        schedulingRepository.save(slot);

        // Clear scheduled time from session
        session.setAvailabilitySlotId(null);
        session.setScheduledAt(null);
        sessionRepository.save(session);

        log.info("Slot booking cancelled for session: {}", sessionId);
    }

    private SlotResponse toSlotResponse(AvailabilitySlot slot) {
        boolean available = slot.getBookedCount() < slot.getMaxInterviews();
        return new SlotResponse(
                slot.getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getMaxInterviews(),
                slot.getBookedCount(),
                available
        );
    }

    private void validateSlot(CreateSlotRequest request) {
        if (request.startTime().isAfter(request.endTime())) {
            throw new BadRequestException("Slot start time must be before end time");
        }

        if (request.startTime().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Cannot create slots in the past");
        }

        if (request.maxInterviews() <= 0) {
            throw new BadRequestException("Max interviews must be greater than 0");
        }
    }
}
