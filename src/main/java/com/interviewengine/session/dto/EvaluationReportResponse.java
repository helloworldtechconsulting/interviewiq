package com.interviewengine.session.dto;

import com.interviewengine.ai.domain.HiringRecommendation;
import com.interviewengine.session.domain.EvaluationReport;
import com.interviewengine.shared.domain.PipelineStatus;

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
        /** The recruiter's own notes (INTIQ-29). Never used in scoring. */
        String               employerNotes,
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
                r.getEmployerNotes(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
