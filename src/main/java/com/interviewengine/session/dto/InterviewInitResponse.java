package com.interviewengine.session.dto;

import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InterviewInitResponse(
        UUID           sessionId,
        SessionStatus  status,
        PipelineStatus questionGenerationStatus,
        String         questionsJson,
        String         recordingUploadUrl,
        String         recordingS3Key,
        OffsetDateTime scheduledAt,
        OffsetDateTime inviteExpiresAt,
        boolean        googleVerified
) {}
