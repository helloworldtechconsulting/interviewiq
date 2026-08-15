package com.interviewiq.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.ai.domain.HiringRecommendation;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.WorkerProperties;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled worker that drives the AI evaluation pipeline for completed interview sessions.
 *
 * <h2>Trigger</h2>
 * <p>When the candidate's browser submits {@code POST /api/v1/candidate/interview/complete},
 * {@code SessionService.completeInterview()} transitions the session to {@code COMPLETED} and
 * resets the {@code EvaluationReport.generationStatus} to {@code PENDING}.
 * This worker picks up those PENDING reports on the next poll cycle.
 *
 * <h2>Input to the LLM</h2>
 * <p>The {@code questionsJson} field on the session contains both the pre-generated questions
 * and the candidate's spoken transcripts (merged by {@code SessionService.completeInterview}).
 * Each question object has the shape:
 * <pre>
 * {
 *   "order": 1,
 *   "text": "Tell me about yourself",
 *   "dimension": "COMMUNICATION",
 *   "answer": "I have five years of Java experience..."   ← added by browser
 * }
 * </pre>
 *
 * <p>This eliminates the Recall.ai transcript dependency entirely — no external API
 * calls are needed to fetch the transcript.
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
    private final WorkerProperties            workerProperties;

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
                            WorkerProperties workerProperties) {
        this.evaluationReportRepository = evaluationReportRepository;
        this.sessionRepository          = sessionRepository;
        this.chatClient                 = chatClient;
        this.objectMapper               = objectMapper;
        this.workerProperties           = workerProperties;
    }

    /**
     * Claims a bounded batch of evaluations and runs them.
     *
     * <p><strong>Claiming, not polling.</strong> This previously fetched every
     * PENDING and IN_PROGRESS row with a plain derived query and looped over
     * them. On the 2–6 pods this application now runs on, every pod fetched the
     * same rows: six times the LLM bill, racing writes on the same report row,
     * and {@code generationAttempts} races that fail perfectly healthy sessions.
     * PRD v2.1 §7.9 calls that a hard blocker on autoscaling.
     *
     * <p>{@link EvaluationReportRepository#claimBatch} now claims a distinct,
     * bounded set of rows per pod with {@code FOR UPDATE SKIP LOCKED},
     * incrementing the attempt counter under the same row lock. Rows come back
     * already marked IN_PROGRESS, so this scheduler no longer needs to sweep
     * IN_PROGRESS rows for crash recovery — staleness is handled inside the claim.
     *
     * <p>The 30-second cadence remains a safety net rather than the primary
     * trigger: completion triggers evaluation immediately (§7.5.5), so the
     * report does not wait for the next tick.
     */
    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "PT30S")
    public void evaluatePendingReports() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        List<EvaluationReport> claimed = evaluationReportRepository.claimBatch(
                workerProperties.getEvaluationBatchSize(), staleBefore);

        if (claimed.isEmpty()) return;

        log.debug("EvaluationWorker: claimed {} evaluation(s)", claimed.size());

        for (EvaluationReport report : claimed) {
            self.evaluateSingle(report);  // call through proxy so @Transactional applies
        }
    }

    /**
     * Evaluates one already-claimed report.
     *
     * <p>The caller has claimed this row and incremented its attempt counter
     * under a row lock, so this method must not increment it again — doing so
     * would double-count attempts and retire reports at half the configured
     * limit.
     */
    @Transactional
    public void evaluateSingle(EvaluationReport report) {
        int attempt = report.getGenerationAttempts();

        if (attempt > maxAttempts) {
            log.warn("EvaluationWorker: max attempts ({}) exceeded for reportId={}, marking FAILED",
                    maxAttempts, report.getId());
            report.setGenerationStatus(PipelineStatus.FAILED);
            evaluationReportRepository.save(report);
            return;
        }

        try {
            InterviewSession session = sessionRepository.findById(report.getSessionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Session not found for reportId=" + report.getId()));

            String evaluationJson = callLlm(session, report);

            applyScores(report, evaluationJson);
            report.setEvaluationJson(evaluationJson);
            report.setEvidenceJson(evaluationJson);
            report.setGeneratedAt(OffsetDateTime.now(ZoneOffset.UTC));
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
        String prompt        = buildEvaluationPrompt(questionsJson);

        String rawResponse = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        validateJson(rawResponse, report.getId().toString());
        return rawResponse;
    }

    /**
     * Builds the evaluation prompt from the session's {@code questionsJson}.
     *
     * <p>The questions JSON contains both the pre-generated questions and the
     * candidate's spoken answers (merged during session completion).
     *
     * <p>If a question's {@code "answer"} field is absent or empty (e.g. the
     * candidate skipped a question or the STT engine returned nothing), the
     * evaluator will treat it as a blank answer.
     */
    private String buildEvaluationPrompt(String questionsJson) {
        String qaSection = formatQuestionsForPrompt(questionsJson);

        return """
                You are an expert interview evaluator. Evaluate the following AI-conducted interview.

                The interview was conducted in-browser using text-to-speech (questions) and
                speech-to-text (candidate answers via Web Speech API).

                ## Questions and Candidate Answers
                """ + qaSection + """

                ## Scoring Instructions
                Score each dimension on a 0–10 integer scale:
                - technical: depth and accuracy of technical answers
                - communication: clarity, structure, and articulation
                - relevance: how well answers address the questions asked
                - problem_solving: analytical thinking and structured reasoning

                Compute overall_score as an integer 0–100 weighted composite:
                  overall = (technical * 3 + communication * 2 + relevance * 3 + problem_solving * 2)

                If a candidate's answer is blank or very short, score that question's dimensions low.
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

    private String formatQuestionsForPrompt(String questionsJson) {
        if (questionsJson == null || questionsJson.isBlank()) {
            return "[NO QUESTIONS AVAILABLE — question generation may not have completed]";
        }

        try {
            JsonNode questions = objectMapper.readTree(questionsJson);
            if (!questions.isArray() || questions.isEmpty()) {
                return questionsJson;
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode q : questions) {
                int order = q.path("order").asInt();
                String text = q.path("text").asText("");
                String answer = q.path("answer").asText("").strip();
                String dimension = q.path("dimension").asText("");

                sb.append("Q").append(order);
                if (!dimension.isBlank()) sb.append(" [").append(dimension).append("]");
                sb.append(": ").append(text).append("\n");
                sb.append("A: ");
                if (answer.isBlank()) {
                    sb.append("[No answer provided]");
                } else {
                    sb.append(answer);
                }
                sb.append("\n\n");
            }
            return sb.toString().strip();

        } catch (JsonProcessingException e) {
            log.warn("EvaluationWorker: could not parse questionsJson for formatting — using raw JSON");
            return questionsJson;
        }
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
