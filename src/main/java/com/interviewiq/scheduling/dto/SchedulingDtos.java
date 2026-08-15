package com.interviewiq.scheduling.dto;

import com.interviewiq.job.domain.DurationTier;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request and response shapes for candidate-driven scheduling (PRD v2.1 §7.4).
 *
 * <p>Grouped in one file because they are small, mutually referential, and only
 * meaningful together — this is the four-endpoint scheduling contract from §11.
 */
public final class SchedulingDtos {

    private SchedulingDtos() {}

    /**
     * Answer to "can I start right now?" — the readiness gate that replaced the
     * 30-minute invite buffer (§7.4.3).
     *
     * <p>The buffer was always a proxy for "have the questions finished
     * generating?", and questions generate in about 20 seconds, so the gate
     * measures readiness directly instead. When questions are not ready the page
     * shows "Preparing your interview — ready in about a minute" and polls.
     *
     * @param questionsReady     whether question generation has completed
     * @param capacityAvailable  whether a slot starting now would fit
     * @param canStartNow        both of the above — "Start now" is offered
     * @param earliestBookableAt the next bucket with room, for the time picker
     * @param durationMinutes    the job's tier length, shown to the candidate
     */
    public record ReadinessResponse(
            boolean questionsReady,
            boolean capacityAvailable,
            boolean canStartNow,
            OffsetDateTime earliestBookableAt,
            int durationMinutes,
            DurationTier durationTier
    ) {}

    /**
     * Times the candidate may choose from.
     *
     * <p>Availability is genuinely 24×7 (§7.4.2): there is no business-hours
     * restriction, no blackout period and no quiet-hours setting. If a slot is
     * missing from this list it is because the platform is at capacity for that
     * moment, and for no other reason.
     */
    public record AvailableTimesResponse(
            OffsetDateTime from,
            OffsetDateTime until,
            int durationMinutes,
            List<OffsetDateTime> availableStartTimes
    ) {}

    /** Book, or move an existing booking, to a specific time. */
    public record BookRequest(
            @NotNull(message = "A start time is required.")
            OffsetDateTime startAt
    ) {}

    /**
     * Confirmation of a booking.
     *
     * @param icsDownloadUrl the {@code .ics} attachment sent with the confirmation
     *                       email. Note this is a calendar <em>file</em>, not a
     *                       calendar integration — Google Calendar and Outlook
     *                       integrations are explicitly Phase 2 (§5.2).
     */
    public record BookingResponse(
            OffsetDateTime scheduledStartAt,
            int durationMinutes,
            String status,
            String icsDownloadUrl
    ) {}
}
