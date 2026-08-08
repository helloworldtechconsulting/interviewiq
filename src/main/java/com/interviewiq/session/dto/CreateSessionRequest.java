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

        @NotNull(message = "Scheduled time is required.")
        @Future(message = "Scheduled time must be in the future.")
        OffsetDateTime scheduledAt
) {}
