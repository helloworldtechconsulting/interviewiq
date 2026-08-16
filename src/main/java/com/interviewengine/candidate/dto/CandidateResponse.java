package com.interviewengine.candidate.dto;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateResponse(
        UUID           id,
        UUID           companyId,
        UUID           jobOpeningId,
        String         email,
        String         fullName,
        String         phone,
        String         resumeS3Key,
        PipelineStatus resumeExtractionStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CandidateResponse from(Candidate candidate) {
        return new CandidateResponse(
                candidate.getId(),
                candidate.getCompanyId(),
                candidate.getJobOpeningId(),
                candidate.getEmail(),
                candidate.getFullName(),
                candidate.getPhone(),
                candidate.getResumeS3Key(),
                candidate.getResumeExtractionStatus(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }
}
