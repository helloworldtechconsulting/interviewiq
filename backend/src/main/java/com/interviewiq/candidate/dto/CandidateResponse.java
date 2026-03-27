package com.interviewiq.candidate.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CandidateResponse(
        UUID id,
        String name,
        String email,
        String phone,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
