package com.interviewiq.session.dto;

import com.interviewiq.session.SessionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record InterviewSessionResponse(
        UUID id,
        UUID candidateId,
        UUID jobOpeningId,
        String inviteToken,
        SessionStatus status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer durationSeconds,
        BigDecimal overallScore,
        String recommendation,
        String evaluationSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
