package com.interviewiq.shared.domain;

/**
 * Shared pipeline status for async extraction / generation jobs:
 * <ul>
 *   <li>JD text extraction     — {@code JobOpening.jdExtractionStatus}</li>
 *   <li>Resume text extraction — {@code Candidate.resumeExtractionStatus}</li>
 *   <li>Question generation    — {@code InterviewSession.questionGenerationStatus}</li>
 *   <li>Evaluation generation  — {@code EvaluationReport.generationStatus}</li>
 * </ul>
 * DB CHECK values: 'PENDING', 'IN_PROGRESS', 'DONE', 'FAILED'
 */
public enum PipelineStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED
}
