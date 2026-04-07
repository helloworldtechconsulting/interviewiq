package com.interviewiq.session.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSessionRequest(

        @NotNull(message = "Job opening ID is required.")
        UUID jobOpeningId,

        @NotNull(message = "Candidate ID is required.")
        UUID candidateId
) {}
