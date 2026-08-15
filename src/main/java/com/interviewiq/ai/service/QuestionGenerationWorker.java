package com.interviewiq.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.WorkerProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled worker that generates interview questions for sessions awaiting the
 * AI question pipeline.
 *
 * <h2>Input</h2>
 * <ul>
 *   <li><b>JD text</b> (required) — from {@link JobOpening#getJdText()}.
 *       Sessions are only created after {@code jdExtractionStatus == DONE}, so
 *       this is always available when this worker runs.
 *   <li><b>Resume text</b> (optional) — from {@link Candidate#getResumeText()}.
 *       If the resume extraction has not completed, questions are generated from
 *       the JD only (personalisation is treated as best-effort).
 * </ul>
 *
 * <h2>Output</h2>
 * <p>The LLM is prompted to return a JSON array of question objects. The raw
 * response string is validated as parseable JSON before being persisted to
 * {@link InterviewSession#setQuestionsJson(String)}.
 *
 * <h2>Failure handling</h2>
 * <p>Any exception during the OpenAI call (network, quota, invalid JSON) marks
 * the session {@code FAILED}. A separate retry mechanism is intentionally absent
 * — re-triggering is done by resetting the status externally (admin tooling or
 * a future retry endpoint).
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationWorker.class);

    private static final int TARGET_QUESTION_COUNT = 10;

    private final InterviewSessionRepository sessionRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final CandidateRepository        candidateRepository;
    private final WorkerProperties           workerProperties;
    private final ChatClient                 chatClient;
    private final ObjectMapper               objectMapper;

    /**
     * Self-reference injected lazily to route {@link #generateForSession} calls through
     * the Spring AOP proxy, activating the {@code @Transactional} advice.
     */
    @Lazy
    @Autowired
    private QuestionGenerationWorker self;

    public QuestionGenerationWorker(InterviewSessionRepository sessionRepository,
                                    JobOpeningRepository jobOpeningRepository,
                                    CandidateRepository candidateRepository,
                                    WorkerProperties workerProperties,
                                    ChatClient chatClient,
                                    ObjectMapper objectMapper) {
        this.sessionRepository    = sessionRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.candidateRepository  = candidateRepository;
        this.workerProperties     = workerProperties;
        this.chatClient           = chatClient;
        this.objectMapper         = objectMapper;
    }

    /**
     * Poll for PENDING + IN_PROGRESS sessions and generate questions.
     * Runs every 20 seconds after an initial 20-second warm-up.
     *
     * <p>Claims a bounded, distinct batch per pod with {@code FOR UPDATE SKIP
     * LOCKED} (PRD v2.1 §7.9). Previously this fetched every PENDING and
     * IN_PROGRESS row with a plain derived query, which was correct on exactly
     * one instance — on six pods it meant six pods generating questions for the
     * same session and paying the LLM bill six times.
     *
     * <p>Staleness recovery is handled inside the claim rather than by sweeping
     * IN_PROGRESS rows unconditionally, which on more than one pod meant
     * reprocessing work another pod was actively doing.
     */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT20S")
    public void generatePendingQuestions() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        List<InterviewSession> claimed = sessionRepository.claimForQuestionGeneration(
                workerProperties.getQuestionGenerationBatchSize(), staleBefore);

        if (claimed.isEmpty()) return;

        log.debug("QuestionGenerationWorker: claimed {} session(s)", claimed.size());

        for (InterviewSession session : claimed) {
            self.generateForSession(session);  // call through proxy so @Transactional applies
        }
    }

    /**
     * Generates questions for one already-claimed session.
     *
     * <p>The claim has already marked this session IN_PROGRESS under a row lock,
     * so this method does not need to — and must not, because re-marking would
     * reset the claim timestamp and make the row look perpetually fresh to the
     * staleness sweep.
     */
    @Transactional
    public void generateForSession(InterviewSession session) {
        try {
            String questionsJson = callLlm(session);
            session.setQuestionsJson(questionsJson);
            session.setQuestionGenerationStatus(PipelineStatus.DONE);
            // Stamps the readiness gate (§7.4.3): "Start now" becomes available
            // to the candidate the moment this is set.
            session.setQuestionsReadyAt(OffsetDateTime.now(ZoneOffset.UTC));
            sessionRepository.save(session);

            log.info("QuestionGenerationWorker: generated questions for sessionId={}", session.getId());

        } catch (Exception e) {
            log.error("QuestionGenerationWorker: failed to generate questions for sessionId={}",
                    session.getId(), e);
            session.setQuestionGenerationStatus(PipelineStatus.FAILED);
            sessionRepository.save(session);
        }
    }

    private String callLlm(InterviewSession session) throws JsonProcessingException {
        JobOpening job = jobOpeningRepository.findById(session.getJobOpeningId())
                .orElseThrow(() -> new IllegalStateException(
                        "JobOpening not found for sessionId=" + session.getId()));

        Candidate candidate = candidateRepository.findById(session.getCandidateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Candidate not found for sessionId=" + session.getId()));

        String prompt = buildPrompt(job, candidate);

        String rawResponse = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // Validate JSON is parseable before persisting — prevents storing malformed data
        validateJson(rawResponse, session.getId().toString());

        return rawResponse;
    }

    private String buildPrompt(JobOpening job, Candidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert technical interviewer. Generate exactly ")
          .append(TARGET_QUESTION_COUNT)
          .append(" interview questions for the following role.\n\n");

        sb.append("## Job Title\n").append(job.getTitle()).append("\n\n");

        sb.append("## Job Description\n").append(job.getJdText()).append("\n\n");

        String resumeText = candidate.getResumeText();
        if (resumeText != null && !resumeText.isBlank()
                && !resumeText.startsWith("[STUB]") && !resumeText.startsWith("[EMPTY]")) {
            sb.append("## Candidate Resume\n").append(resumeText).append("\n\n");
        } else {
            sb.append("## Candidate Resume\n(Not available — generate questions from JD only)\n\n");
        }

        sb.append("""
                ## Output Format
                Return ONLY a valid JSON array with no additional text, markdown fences, or explanation.
                Each element must follow this schema:
                {
                  "order": <integer 1-""").append(TARGET_QUESTION_COUNT).append("""
                >,
                  "text": "<the interview question>",
                  "dimension": "<one of: TECHNICAL, COMMUNICATION, PROBLEM_SOLVING, RELEVANCE>",
                  "expectedPoints": "<brief notes on what a good answer should cover>"
                }
                """);

        return sb.toString();
    }

    /**
     * Verifies the LLM response is a parseable JSON array.
     * Throws {@link IllegalStateException} if invalid — triggers FAILED status.
     */
    private void validateJson(String json, String sessionIdForLog) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("LLM returned empty response for sessionId=" + sessionIdForLog);
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "LLM returned non-JSON for sessionId=" + sessionIdForLog + ": " + e.getMessage(), e);
        }
    }
}
