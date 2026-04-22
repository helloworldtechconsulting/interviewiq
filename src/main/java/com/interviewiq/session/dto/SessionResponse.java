package com.interviewiq.session.dto;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionResponse(
        UUID           id,
        UUID           companyId,
        UUID           jobOpeningId,
        UUID           candidateId,
        SessionStatus  status,
        PipelineStatus questionGenerationStatus,
        OffsetDateTime scheduledAt,
        OffsetDateTime inviteExpiresAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer        durationSeconds,
        String         recordingS3Key,
        String         proctoringFlagsJsonb,
        OffsetDateTime cancelledAt,
        String         errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static SessionResponse from(InterviewSession s) {
        return new SessionResponse(
                s.getId(),
                s.getCompanyId(),
                s.getJobOpeningId(),
                s.getCandidateId(),
                s.getStatus(),
                s.getQuestionGenerationStatus(),
                s.getScheduledAt(),
                s.getInviteExpiresAt(),
                s.getStartedAt(),
                s.getEndedAt(),
                s.getDurationSeconds(),
                s.getRecordingS3Key(),
                s.getProctoringFlagsJsonb(),
                s.getCancelledAt(),
                s.getErrorCode(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
