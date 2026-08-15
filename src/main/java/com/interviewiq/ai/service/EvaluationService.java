package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.ai.config.AiConfig;
import com.interviewiq.ai.config.AiWorkflowProperties;
import com.interviewiq.ai.domain.HiringRecommendation;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.QuestionSource;
import com.interviewiq.session.domain.SessionAnswer;
import com.interviewiq.session.infrastructure.SessionAnswerRepository;
import com.interviewiq.shared.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores one interview (PRD v2.1 §7.5.5, §7.6).
 *
 * <p>Extracted from {@code EvaluationWorker} so the worker is responsible for
 * claiming and retrying while this is responsible for producing a report. The
 * split matters because the evidence requirement below is a <em>retryable</em>
 * failure, and mixing that with claim bookkeeping is how attempt counters go
 * wrong.
 *
 * <h2>The four things every evaluation must do</h2>
 *
 * <ol>
 *   <li><strong>Redact PII first.</strong> Mandatory on every outbound LLM call,
 *       for every workflow (§7.5.6). The transcript goes out carrying an opaque
 *       {@code candidate_ref} and nothing else identifying.</li>
 *   <li><strong>Use the configured evaluation vendor.</strong> Injected by
 *       qualifier, so switching between Haiku and GPT-5.4-mini is a config flip
 *       (§9.1) — and so shadow mode can run both.</li>
 *   <li><strong>Validate the evidence before persisting.</strong> §7.6: "a
 *       report whose narrative does not cite answers is a defect, not a
 *       stylistic preference." A response that fails validation is rejected and
 *       the attempt retried, rather than shown to a recruiter as complete.</li>
 *   <li><strong>Score from the answer rows,</strong> not from a merged JSON
 *       blob, so a candidate who dropped off mid-interview is scored on exactly
 *       what they answered.</li>
 * </ol>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final ChatClient evaluationClient;
    private final ChatClient shadowClient;
    private final PromptTemplateService prompts;
    private final PiiRedactionService redaction;
    private final EvidenceValidator evidenceValidator;
    private final SessionAnswerRepository answerRepository;
    private final CandidateRepository candidateRepository;
    private final JobOpeningRepository jobRepository;
    private final AiWorkflowProperties aiProperties;
    private final QuestionRetirementService retirementService;
    private final ObjectMapper objectMapper;

    public EvaluationService(
            @Qualifier(AiConfig.EVALUATION_CLIENT) ChatClient evaluationClient,
            @Qualifier(AiConfig.SHADOW_CLIENT) ObjectProvider<ChatClient> shadowClientProvider,
            PromptTemplateService prompts,
            PiiRedactionService redaction,
            EvidenceValidator evidenceValidator,
            SessionAnswerRepository answerRepository,
            CandidateRepository candidateRepository,
            JobOpeningRepository jobRepository,
            AiWorkflowProperties aiProperties,
            QuestionRetirementService retirementService,
            ObjectMapper objectMapper) {
        this.evaluationClient  = evaluationClient;
        // ObjectProvider, not a direct ChatClient. AiConfig's shadow bean method
        // returns null when shadow mode is off, and Spring reads that as "no
        // such bean" — a plain constructor parameter therefore fails to resolve
        // and the whole application refuses to start on the default config.
        this.shadowClient      = shadowClientProvider.getIfAvailable();
        this.prompts           = prompts;
        this.redaction         = redaction;
        this.evidenceValidator = evidenceValidator;
        this.answerRepository  = answerRepository;
        this.candidateRepository = candidateRepository;
        this.jobRepository     = jobRepository;
        this.aiProperties      = aiProperties;
        this.retirementService = retirementService;
        this.objectMapper      = objectMapper;
    }

    /**
     * Produces a scored, evidence-backed report for one session.
     *
     * @throws AiServiceException if the model returns unusable JSON, or evidence
     *         that does not cite real answers — both retryable by the caller
     */
    public void evaluate(InterviewSession session, EvaluationReport report) {
        List<SessionAnswer> answers =
                answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(session.getId());

        if (answers.isEmpty()) {
            throw new AiServiceException(
                    "No answers recorded for session " + session.getId() + "; nothing to evaluate.");
        }

        String prompt = buildPrompt(session, answers);
        String raw = call(evaluationClient, prompt, session);

        JsonNode parsed = parse(raw, session);
        List<String> problems = evidenceValidator.validate(parsed, answers.size());
        if (!problems.isEmpty()) {
            // Retryable on purpose. The prompt asks for citations explicitly, so
            // a response without them is a model that did not comply — and
            // another attempt is the right answer, not a degraded report.
            throw new AiServiceException(
                    "Evaluation lacked citable evidence and will be retried: "
                            + String.join("; ", problems));
        }

        applyTo(report, parsed, raw, answers);
        scoreAnswers(answers, parsed);

        runShadowEvaluation(session, prompt);
    }

    // =========================================================================
    // Prompt
    // =========================================================================

    private String buildPrompt(InterviewSession session, List<SessionAnswer> answers) {
        Candidate candidate = candidateRepository.findById(session.getCandidateId()).orElse(null);
        JobOpening job = jobRepository.findById(session.getJobOpeningId()).orElse(null);

        String jdText = job == null ? "" : job.getJdText();
        String redactedJd = redaction.redact(jdText, candidate);

        StringBuilder qa = new StringBuilder();
        List<Integer> employerIndexes = new ArrayList<>();

        for (SessionAnswer answer : answers) {
            if (answer.getQuestionSource() == QuestionSource.EMPLOYER) {
                employerIndexes.add(answer.getQuestionIndex());
            }
            qa.append("[").append(answer.getQuestionIndex()).append("] Q: ")
              .append(redaction.redact(answer.getQuestionText(), candidate)).append("\n");
            qa.append("      A: ");
            if (answer.isSkipped() || answer.getTranscriptText() == null) {
                qa.append("[skipped — no answer given]");
            } else {
                qa.append(redaction.redact(answer.getTranscriptText(), candidate));
            }
            qa.append("\n\n");
        }

        long answered = answers.stream().filter(SessionAnswer::isAnswered).count();

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("jdText", redactedJd);
        attributes.put("durationMinutes", session.getDurationTier().getMinutes());
        attributes.put("candidateRef", candidate == null ? "candidate" : candidate.getCandidateRef());
        attributes.put("questionsAndAnswers", qa.toString().strip());
        attributes.put("incomplete", answered < answers.size() ? Boolean.TRUE : null);
        attributes.put("answeredCount", answered);
        attributes.put("totalCount", answers.size());
        attributes.put("employerQuestionIndexes", employerIndexes.isEmpty() ? null : employerIndexes);

        String prompt = prompts.render(PromptTemplateService.EVALUATION, attributes);

        // Belt-and-braces: the payload is assembled from already-redacted parts,
        // and this confirms nothing slipped through before it leaves the building.
        redaction.verifyRedacted(prompt, "evaluation prompt for session " + session.getId());

        return prompt;
    }

    private String call(ChatClient client, String prompt, InterviewSession session) {
        try {
            String content = client.prompt().user(prompt).call().content();
            if (content == null || content.isBlank()) {
                throw new AiServiceException("The evaluation model returned an empty response.");
            }
            return stripFences(content);
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new AiServiceException(
                    "Evaluation call failed for session " + session.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Models still wrap JSON in markdown fences occasionally, despite the prompt
     * saying not to. Cheaper to strip than to burn a retry on.
     */
    private String stripFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private JsonNode parse(String raw, InterviewSession session) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiServiceException(
                    "The evaluation model returned unparseable JSON for session " + session.getId());
        }
    }

    // =========================================================================
    // Persisting
    // =========================================================================

    private void applyTo(EvaluationReport report, JsonNode parsed, String raw, List<SessionAnswer> answers) {
        report.setOverallScore(shortAt(parsed, "overall_score"));
        report.setTechnicalScore(shortAt(parsed, "technical_score"));
        report.setCommunicationScore(shortAt(parsed, "communication_score"));
        report.setRelevanceScore(shortAt(parsed, "relevance_score"));
        report.setProblemSolvingScore(shortAt(parsed, "problem_solving_score"));

        String recommendation = parsed.path("recommendation").asText("");
        if (!recommendation.isBlank()) {
            try {
                report.setRecommendation(HiringRecommendation.valueOf(recommendation));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown recommendation band '{}' — leaving it unset", recommendation);
            }
        }

        report.setSummaryText(parsed.path("summary").asText(null));
        report.setEvaluationJson(raw);
        // The evidence subtree is what the report page renders; it is stored
        // separately from the raw response so the report does not depend on the
        // model's full output shape staying stable.
        report.setEvidenceJson(evidenceSubtree(parsed));
        report.setPartial(answers.stream().anyMatch(a -> !a.isAnswered()));
    }

    private String evidenceSubtree(JsonNode parsed) {
        try {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("overallSummary", parsed.path("summary").asText(""));
            evidence.put("dimensions", objectMapper.treeToValue(parsed.path("dimensions"), Map.class));
            evidence.put("perQuestion", objectMapper.treeToValue(parsed.path("perQuestion"), List.class));
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            // Validation already passed, so this should not happen — but a report
            // must never be persisted DONE without evidence (the CHECK enforces
            // it too), so fail loudly rather than write a null.
            throw new AiServiceException("Could not extract the evidence subtree: " + e.getMessage());
        }
    }

    /** Writes the per-question scores back onto the answer rows. */
    private void scoreAnswers(List<SessionAnswer> answers, JsonNode parsed) {
        Map<Integer, Short> byIndex = new HashMap<>();
        for (JsonNode entry : parsed.path("perQuestion")) {
            int index = entry.path("questionIndex").asInt(-1);
            if (index >= 0 && entry.has("score")) {
                byIndex.put(index, (short) Math.clamp(entry.path("score").asInt(0), 0, 10));
            }
        }
        for (SessionAnswer answer : answers) {
            Short score = byIndex.get(answer.getQuestionIndex());
            if (score != null) {
                answer.setScore(score);
            }
        }
        answerRepository.saveAll(answers);

        foldScoresIntoTelemetry(answers);
    }

    /**
     * Folds the newly written scores into each question's running variance
     * (INTIQ-93 item 4).
     *
     * <p>This is the second half of a two-part record. The interview room counts
     * the ask and the skip as they happen; the score does not exist until here,
     * minutes later. Without this call the variance would stay at zero for every
     * question — which the retirement rules would read as "discriminates
     * nothing" and act on, retiring the entire bank.
     *
     * <p>Only scored, non-skipped, bank-sourced answers contribute. A skipped
     * question was already counted when it was skipped, and counting it again
     * here would double its weight in the ask total.
     *
     * <p>Failures are absorbed: the evaluation is written and the candidate has
     * their report, and a lost telemetry point is a gap in a statistic rather
     * than a defect in the product.
     */
    private void foldScoresIntoTelemetry(List<SessionAnswer> answers) {
        for (SessionAnswer answer : answers) {
            if (answer.getBankQuestionId() == null || answer.getScore() == null || answer.isSkipped()) {
                continue;
            }
            try {
                retirementService.recordScore(
                        answer.getSessionId(), answer.getBankQuestionId(), answer.getScore());
            } catch (RuntimeException e) {
                log.warn("Question telemetry score not recorded: sessionId={} questionId={}",
                        answer.getSessionId(), answer.getBankQuestionId(), e);
            }
        }
    }

    // =========================================================================
    // Shadow mode (§13.1)
    // =========================================================================

    /**
     * Scores the same interview with the second vendor and logs the result.
     *
     * <p>"Score every one of the first ~50 interviews with both GPT-5.4-mini and
     * Claude Haiku 4.5, serve one to the recruiter and log both." That yields a
     * real Pearson r per vendor against hire outcomes for about ₹900 total —
     * which is the only way to answer a question public benchmarks cannot.
     *
     * <p>Never throws. The shadow result is an experiment; a failure in it must
     * not fail the evaluation the recruiter is actually waiting for.
     */
    private void runShadowEvaluation(InterviewSession session, String prompt) {
        if (!aiProperties.isShadowEvaluation() || shadowClient == null) {
            return;
        }
        try {
            JsonNode shadow = parse(call(shadowClient, prompt, session), session);
            log.info("Shadow evaluation: sessionId={} vendor={}/{} overall={} recommendation={}",
                    session.getId(),
                    aiProperties.getShadowVendor(),
                    aiProperties.getShadowModel(),
                    shadow.path("overall_score").asInt(-1),
                    shadow.path("recommendation").asText(""));
        } catch (Exception e) {
            log.warn("Shadow evaluation failed for sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    private Short shortAt(JsonNode node, String field) {
        return node.has(field) ? (short) node.path(field).asInt(0) : null;
    }
}
