package com.interviewiq.session.dto;

import com.interviewiq.ai.domain.HiringRecommendation;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EvaluationReportResponse(
        UUID                 id,
        UUID                 companyId,
        UUID                 sessionId,
        Short                overallScore,
        Short                technicalScore,
        Short                communicationScore,
        Short                relevanceScore,
        Short                problemSolvingScore,
        HiringRecommendation recommendation,
        PipelineStatus       generationStatus,
        String               evaluationJson,
        OffsetDateTime       createdAt,
        OffsetDateTime       updatedAt
) {
    public static EvaluationReportResponse from(EvaluationReport r) {
        return new EvaluationReportResponse(
                r.getId(),
                r.getCompanyId(),
                r.getSessionId(),
                r.getOverallScore(),
                r.getTechnicalScore(),
                r.getCommunicationScore(),
                r.getRelevanceScore(),
                r.getProblemSolvingScore(),
                r.getRecommendation(),
                r.getGenerationStatus(),
                r.getEvaluationJson(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
