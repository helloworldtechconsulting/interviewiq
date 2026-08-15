package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.domain.EmployerQuestion;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.EmployerQuestionRepository;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.WorkerProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 2 of question generation: assembles one candidate's question set from
 * the opening's bank (PRD v2.1 §7.5, INTIQ-17).
 *
 * <h2>What changed, and why it matters</h2>
 *
 * <p>This worker used to generate a full question set per candidate from an
 * inline prompt string, and validate the response only by checking it parsed.
 * Three things were wrong with that:
 *
 * <ul>
 *   <li><strong>The prompt was inline.</strong> {@code question-generation.st}
 *       existed and was never read — the one workflow INTIQ-75 named first was
 *       the one still concatenating Java strings.</li>
 *   <li><strong>The safety filter never ran on generated questions.</strong>
 *       {@link QuestionSafetyFilter} was applied to employer-supplied questions
 *       and to live follow-ups but not here, which is the case it was built for.
 *       The prompt asks the model to avoid protected attributes; a prompt
 *       instruction is a request, not a control.</li>
 *   <li><strong>Every candidate cost a full generation call,</strong> and 25
 *       candidates on one opening got 25 near-identical sets built from the same
 *       JD — expensive and non-comparable at the same time.</li>
 * </ul>
 *
 * <p>All three are addressed by moving generation to the job level
 * ({@link QuestionBankService}) and leaving this worker to assemble. The bank is
 * template-driven and screened; assembly is pure code plus at most one small
 * resume call.
 *
 * <h2>Waiting for the bank is not failing</h2>
 *
 * <p>A session whose opening has no bank yet is left {@code PENDING} rather than
 * marked {@code FAILED}. The bank usually lands within seconds of the JD
 * extracting, and failing a session for arriving slightly early would burn one
 * of its attempts for a condition that resolves itself.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationWorker.class);

    private final InterviewSessionRepository sessionRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final CandidateRepository        candidateRepository;
    private final EmployerQuestionRepository employerQuestionRepository;
    private final WorkerProperties           workerProperties;
    private final QuestionAssemblyService    assemblyService;
    private final QuestionSafetyFilter       safetyFilter;
    private final PromptTemplateService      prompts;
    private final PiiRedactionService        piiRedaction;
    private final ChatClient                 chatClient;
    private final ObjectMapper               objectMapper;

    /** Self-reference so the transactional boundaries below actually apply. */
    @Lazy
    @Autowired
    private QuestionGenerationWorker self;

    public QuestionGenerationWorker(InterviewSessionRepository sessionRepository,
                                    JobOpeningRepository jobOpeningRepository,
                                    CandidateRepository candidateRepository,
                                    EmployerQuestionRepository employerQuestionRepository,
                                    WorkerProperties workerProperties,
                                    QuestionAssemblyService assemblyService,
                                    QuestionSafetyFilter safetyFilter,
                                    PromptTemplateService prompts,
                                    PiiRedactionService piiRedaction,
                                    @Qualifier("questionChatClient") ChatClient chatClient,
                                    ObjectMapper objectMapper) {
        this.sessionRepository          = sessionRepository;
        this.jobOpeningRepository       = jobOpeningRepository;
        this.candidateRepository        = candidateRepository;
        this.employerQuestionRepository = employerQuestionRepository;
        this.workerProperties           = workerProperties;
        this.assemblyService            = assemblyService;
        this.safetyFilter               = safetyFilter;
        this.prompts                    = prompts;
        this.piiRedaction               = piiRedaction;
        this.chatClient                 = chatClient;
        this.objectMapper               = objectMapper;
    }

    /**
     * Claims a bounded, distinct batch per pod with {@code FOR UPDATE SKIP
     * LOCKED} (§7.9), then assembles each set outside the claim's transaction.
     */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT20S")
    public void generatePendingQuestions() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        List<InterviewSession> claimed = self.claim(staleBefore);
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("QuestionGenerationWorker: claimed {} session(s)", claimed.size());
        for (InterviewSession session : claimed) {
            assembleFor(session);
        }
    }

    @Transactional
    public List<InterviewSession> claim(OffsetDateTime staleBefore) {
        return sessionRepository.claimForQuestionGeneration(
                workerProperties.getQuestionGenerationBatchSize(), staleBefore);
    }

    /**
     * Assembles one session's questions. Any model call happens here, outside a
     * transaction.
     */
    private void assembleFor(InterviewSession session) {
        try {
            JobOpening job = jobOpeningRepository.findById(session.getJobOpeningId())
                    .orElseThrow(() -> new AiServiceException(
                            "JobOpening missing for session " + session.getId()));

            if (job.getQuestionBankStatus() != PipelineStatus.DONE || job.getQuestionBankJsonb() == null) {
                // The bank is still being generated. Release the claim back to
                // PENDING rather than failing — this resolves itself in seconds
                // and burning an attempt on it would be wrong.
                self.releaseForRetry(session.getId());
                log.debug("QuestionGenerationWorker: bank not ready for jobId={}, deferring sessionId={}",
                        job.getId(), session.getId());
                return;
            }

            Candidate candidate = candidateRepository.findById(session.getCandidateId())
                    .orElseThrow(() -> new AiServiceException(
                            "Candidate missing for session " + session.getId()));

            List<String> employerQuestions = employerQuestionRepository
                    .findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(job.getId())
                    .stream()
                    .filter(EmployerQuestion::isUsable)
                    .map(EmployerQuestion::getQuestionText)
                    .toList();

            List<String> resumeQuestions = generateResumeQuestions(job, candidate, session);

            String questionsJson = assemblyService.assemble(
                    job.getQuestionBankJsonb(),
                    employerQuestions,
                    resumeQuestions,
                    session.getDurationTier(),
                    candidate.getId());

            self.recordSuccess(session.getId(), questionsJson, resumeQuestions.isEmpty());
            log.info("QuestionGenerationWorker: assembled questions for sessionId={} resumeQuestions={}",
                    session.getId(), resumeQuestions.size());

        } catch (Exception e) {
            log.error("QuestionGenerationWorker: assembly failed for sessionId={}", session.getId(), e);
            self.recordFailure(session.getId());
        }
    }

    /**
     * Generates the resume-anchored questions, or none when there is no résumé.
     *
     * <p>The résumé is PII-redacted before it leaves for the model (§7.10,
     * INTIQ-36) — the questions are about what the candidate has done, not who
     * they are, so their name and contact details have no business in the
     * prompt.
     *
     * <p>Every returned question is screened. The résumé is free text supplied by
     * the candidate, which makes this the one generation path where prompt
     * content is partly attacker-controlled; the filter running afterwards is
     * what makes that safe rather than merely unlikely to matter.
     *
     * <p>A failure here degrades to an empty list rather than failing the
     * session. An interview drawn entirely from the bank is a worse interview;
     * no interview at all is worse still.
     */
    private List<String> generateResumeQuestions(JobOpening job, Candidate candidate, InterviewSession session) {
        String resumeText = candidate.getResumeText();
        if (resumeText == null || resumeText.isBlank()
                || resumeText.startsWith("[STUB]") || resumeText.startsWith("[EMPTY]")) {
            return List.of();
        }

        try {
            String redacted = piiRedaction.redact(resumeText);
            int wanted = Math.max(1, session.getDurationTier().getQuestionCount() / 5);

            String prompt = prompts.render(PromptTemplateService.QUESTION_GENERATION, Map.of(
                    "jdText", job.getJdText() == null ? "" : job.getJdText(),
                    "resumeText", redacted,
                    "questionCount", wanted,
                    "durationMinutes", session.getDurationTier().getMinutes(),
                    "employerQuestions", List.of(),
                    "candidateRef", candidate.getCandidateRef()));

            String raw = chatClient.prompt().user(prompt).call().content();
            return screenAll(parseTexts(raw), session.getId());

        } catch (Exception e) {
            log.warn("Resume-anchored question generation failed for sessionId={}; "
                    + "continuing with bank questions only", session.getId(), e);
            return List.of();
        }
    }

    /** Drops anything the prohibited-topic filter refuses, logging each drop. */
    private List<String> screenAll(List<String> questions, UUID sessionIdForLog) {
        List<String> safe = new ArrayList<>(questions.size());
        for (String q : questions) {
            QuestionSafetyFilter.Verdict verdict = safetyFilter.screen(q);
            if (verdict.approved()) {
                safe.add(q);
            } else {
                log.warn("Resume question dropped by filter: sessionId={} category={} text={}",
                        sessionIdForLog, verdict.prohibitedCategory(), q);
            }
        }
        return safe;
    }

    private List<String> parseTexts(String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }
        JsonNode tree = objectMapper.readTree(cleaned);
        List<String> texts = new ArrayList<>();
        if (tree.isArray()) {
            for (JsonNode n : tree) {
                String text = n.path("text").asText("").strip();
                if (!text.isEmpty()) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    // =========================================================================
    // Terminal state writes, each in its own transaction
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID sessionId, String questionsJson, boolean resumeMissing) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setQuestionsJson(questionsJson);
            session.setQuestionGenerationStatus(PipelineStatus.DONE);
            session.setResumeMissing(resumeMissing);
            // Stamps the readiness gate (§7.4.3): "Start now" becomes available
            // to the candidate the moment this is set.
            session.setQuestionsReadyAt(OffsetDateTime.now(ZoneOffset.UTC));
            sessionRepository.save(session);
        });
    }

    /**
     * Returns a session to {@code PENDING} so the next pass retries it.
     *
     * <p>Used when the opening's bank is not ready yet — a transient condition
     * rather than a fault.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseForRetry(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setQuestionGenerationStatus(PipelineStatus.PENDING);
            sessionRepository.save(session);
        });
    }

    /**
     * Marks the session failed in a fresh transaction.
     *
     * <p>{@code REQUIRES_NEW} for the reason INTIQ-81 documented: a failure
     * marker written inside an already-doomed transaction never commits, so the
     * session is re-claimed forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setQuestionGenerationStatus(PipelineStatus.FAILED);
            sessionRepository.save(session);
        });
    }
}
