package com.interviewiq.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.ai.domain.HiringRecommendation;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.domain.PipelineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled worker that drives the AI evaluation pipeline for completed interview sessions.
 *
 * <h2>Trigger</h2>
 * <p>The {@code bot.done} webhook transitions the session to {@code COMPLETED} and
 * sets the {@code EvaluationReport.generationStatus} to {@code PENDING} (it was created
 * as a stub in {@code SessionService.create()}). This worker picks up those PENDING reports.
 *
 * <h2>Input to the LLM</h2>
 * <ul>
 *   <li>The pre-generated {@code questionsJson} from the session (produced by
 *       {@link QuestionGenerationWorker}).
 *   <li>In a production integration: the session transcript downloaded from the
 *       Recall.ai API using the {@code recallBotId}. In the current stub-mode
 *       implementation, a placeholder transcript is used when the Recall.ai API key
 *       is blank.
 * </ul>
 *
 * <h2>Output</h2>
 * <p>The LLM returns a structured JSON with per-dimension scores and an overall
 * recommendation. Scalar scores are extracted from JSON and written to dedicated
 * typed columns for aggregation (SQL {@code AVG}, {@code ORDER BY}).
 *
 * <h2>Max attempts</h2>
 * <p>Each invocation increments {@code generationAttempts}. Once the attempt count
 * reaches {@code app.ai.evaluation-max-attempts} (default: 3), the report is
 * permanently marked {@code FAILED} to prevent infinite retry loops.
 */
@Component
public class EvaluationWorker {

    private static final Logger log = LoggerFactory.getLogger(EvaluationWorker.class);

    @Value("${app.ai.evaluation-max-attempts:3}")
    private int maxAttempts;

    private final EvaluationReportRepository  evaluationReportRepository;
    private final InterviewSessionRepository  sessionRepository;
    private final ChatClient                  chatClient;
    private final ObjectMapper                objectMapper;
    private final RecallTranscriptClient      recallTranscriptClient;

    /**
     * Self-reference injected lazily to route {@link #evaluateSingle} calls through
     * the Spring AOP proxy, activating the {@code @Transactional} advice.
     */
    @Lazy
    @Autowired
    private EvaluationWorker self;

    public EvaluationWorker(EvaluationReportRepository evaluationReportRepository,
                            InterviewSessionRepository sessionRepository,
                            ChatClient chatClient,
                            ObjectMapper objectMapper,
                            RecallTranscriptClient recallTranscriptClient) {
        this.evaluationReportRepository = evaluationReportRepository;
        this.sessionRepository          = sessionRepository;
        this.chatClient                 = chatClient;
        this.objectMapper               = objectMapper;
        this.recallTranscriptClient     = recallTranscriptClient;
    }

    /**
     * Poll for PENDING + IN_PROGRESS evaluation reports and run the AI evaluation.
     * Runs every 30 seconds with an initial 25-second warm-up.
     *
     * <p>IN_PROGRESS items are included for crash recovery. Safe because
     * {@code fixedDelay} prevents concurrent scheduler runs within a single JVM.
     */
    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "PT30S")
    public void evaluatePendingReports() {
        List<EvaluationReport> workItems =
                evaluationReportRepository.findAllByGenerationStatusIn(
                        List.of(PipelineStatus.PENDING, PipelineStatus.IN_PROGRESS));

        if (workItems.isEmpty()) return;

        log.debug("EvaluationWorker: processing {} evaluation(s) (PENDING + IN_PROGRESS recovery)",
                workItems.size());

        for (EvaluationReport report : workItems) {
            self.evaluateSingle(report);  // call through proxy so @Transactional applies
        }
    }

    @Transactional
    public void evaluateSingle(EvaluationReport report) {
        int attempt = report.getGenerationAttempts() + 1;
        report.setGenerationAttempts(attempt);

        if (attempt > maxAttempts) {
            log.warn("EvaluationWorker: max attempts ({}) exceeded for reportId={}, marking FAILED",
                    maxAttempts, report.getId());
            report.setGenerationStatus(PipelineStatus.FAILED);
            evaluationReportRepository.save(report);
            return;
        }

        // Mark IN_PROGRESS
        report.setGenerationStatus(PipelineStatus.IN_PROGRESS);
        evaluationReportRepository.save(report);

        try {
            InterviewSession session = sessionRepository.findById(report.getSessionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Session not found for reportId=" + report.getId()));

            String evaluationJson = callLlm(session, report);

            applyScores(report, evaluationJson);
            report.setEvaluationJson(evaluationJson);
            report.setGenerationStatus(PipelineStatus.DONE);
            evaluationReportRepository.save(report);

            log.info("EvaluationWorker: evaluation complete for sessionId={} reportId={}",
                    session.getId(), report.getId());

        } catch (Exception e) {
            log.error("EvaluationWorker: attempt {} failed for reportId={}", attempt, report.getId(), e);
            // Revert to PENDING so next poll can retry (up to maxAttempts)
            report.setGenerationStatus(PipelineStatus.PENDING);
            evaluationReportRepository.save(report);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String callLlm(InterviewSession session, EvaluationReport report) throws JsonProcessingException {
        String questionsJson = session.getQuestionsJson();
        String transcript    = resolveTranscript(session);
        String prompt        = buildEvaluationPrompt(questionsJson, transcript);

        String rawResponse = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        validateJson(rawResponse, report.getId().toString());
        return rawResponse;
    }

    /**
     * Resolves the interview transcript for the given session.
     *
     * <p>Delegates to {@link RecallTranscriptClient}, which:
     * <ul>
     *   <li>Returns a stub transcript when {@code app.recall.api-key} is blank
     *       (local / CI mode) — allows the evaluation pipeline to run end-to-end
     *       without a live Recall.ai account.</li>
     *   <li>Calls {@code GET https://api.recall.ai/api/v1/bot/{botId}/transcript/}
     *       with full pagination support in production.</li>
     * </ul>
     *
     * <p>When {@code recallBotId} is null or blank the session has no recording
     * (e.g. the candidate never joined), so a placeholder text is returned
     * rather than making a pointless network call. The evaluator will still
     * run and produce scores, but they will reflect the absence of content.
     *
     * @throws BotServiceException propagated from {@link RecallTranscriptClient}
     *         on HTTP error or network failure — causes this attempt to be
     *         retried on the next poll cycle.
     */
    private String resolveTranscript(InterviewSession session) {
        String botId = session.getRecallBotId();
        if (botId == null || botId.isBlank()) {
            log.info("EvaluationWorker: no recallBotId for sessionId={} — no recording available",
                    session.getId());
            return "[NO RECORDING — candidate did not join the interview session]";
        }
        return recallTranscriptClient.fetchTranscript(botId);
    }

    private String buildEvaluationPrompt(String questionsJson, String transcript) {
        return """
                You are an expert interview evaluator. Evaluate the following interview session.

                ## Interview Questions
                """ + questionsJson + """

                ## Interview Transcript
                """ + transcript + """

                ## Scoring Instructions
                Score each dimension on a 0–10 integer scale:
                - technical: depth and accuracy of technical answers
                - communication: clarity, structure, and articulation
                - relevance: how well answers address the questions asked
                - problem_solving: analytical thinking and structured reasoning

                Compute overall_score as an integer 0–100 weighted composite:
                  overall = (technical * 3 + communication * 2 + relevance * 3 + problem_solving * 2)

                Choose recommendation from: STRONG_HIRE, HIRE, NO_HIRE, STRONG_NO_HIRE

                ## Output Format
                Return ONLY a valid JSON object with no additional text, markdown fences, or explanation:
                {
                  "overall_score": <integer 0-100>,
                  "technical_score": <integer 0-10>,
                  "communication_score": <integer 0-10>,
                  "relevance_score": <integer 0-10>,
                  "problem_solving_score": <integer 0-10>,
                  "recommendation": "<STRONG_HIRE|HIRE|NO_HIRE|STRONG_NO_HIRE>",
                  "summary": "<2-3 sentence overall assessment>",
                  "strengths": ["<strength 1>", "<strength 2>"],
                  "areas_for_improvement": ["<area 1>", "<area 2>"]
                }
                """;
    }

    /**
     * Extracts scalar scores from the LLM JSON response and applies them to the
     * {@link EvaluationReport} entity fields used for DB aggregation.
     */
    private void applyScores(EvaluationReport report, String evaluationJson) {
        try {
            JsonNode node = objectMapper.readTree(evaluationJson);

            report.setOverallScore(safeShort(node, "overall_score"));
            report.setTechnicalScore(safeShort(node, "technical_score"));
            report.setCommunicationScore(safeShort(node, "communication_score"));
            report.setRelevanceScore(safeShort(node, "relevance_score"));
            report.setProblemSolvingScore(safeShort(node, "problem_solving_score"));

            String rec = node.path("recommendation").asText("");
            if (!rec.isBlank()) {
                try {
                    report.setRecommendation(HiringRecommendation.valueOf(rec));
                } catch (IllegalArgumentException e) {
                    log.warn("EvaluationWorker: unknown recommendation '{}', leaving null", rec);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("EvaluationWorker: could not parse evaluation JSON for scores, leaving nulls: {}", e.getMessage());
        }
    }

    private Short safeShort(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.shortValue();
    }

    private void validateJson(String json, String reportIdForLog) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("LLM returned empty response for reportId=" + reportIdForLog);
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "LLM returned non-JSON for reportId=" + reportIdForLog + ": " + e.getMessage(), e);
        }
    }
}
