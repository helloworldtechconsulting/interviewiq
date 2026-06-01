package com.interviewiq.session.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateSessionRequest(

        @NotNull(message = "Job opening ID is required.")
        UUID jobOpeningId,

        @NotNull(message = "Candidate ID is required.")
        UUID candidateId,

        /**
         * Target interview time set by the recruiter.
         * Must be a future timestamp. Shown to the candidate in the invite email
         * and on the CandidateRoomPage. Stored in interview_sessions.scheduled_at (V033).
         */
        @NotNull(message = "Scheduled time is required.")
        @Future(message = "Scheduled time must be in the future.")
        OffsetDateTime scheduledAt
) {}
