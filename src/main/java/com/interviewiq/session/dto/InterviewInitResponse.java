package com.interviewiq.session.dto;

import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Returned by {@code GET /api/v1/candidate/interview/init}.
 *
 * <p>Contains everything the browser needs to run the interview room:
 * <ul>
 *   <li>{@code questionsJson} — the pre-generated question set as a JSON string.</li>
 *   <li>{@code recordingUploadUrl} — pre-signed S3 PUT URL for the WebM recording.</li>
 *   <li>{@code recordingS3Key} — the S3 key the browser must PUT to (for backend tracking).</li>
 *   <li>{@code googleVerified} — whether the candidate has already verified their Google
 *       identity. When {@code false}, the frontend shows the {@code GOOGLE_AUTH} phase
 *       before camera setup.</li>
 *   <li>Session lifecycle metadata for UI state initialisation.</li>
 * </ul>
 */
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
