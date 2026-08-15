package com.interviewiq.job.service;

import com.interviewiq.ai.service.QuestionSafetyFilter;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.job.domain.EmployerQuestion;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.QuestionSafetyStatus;
import com.interviewiq.job.infrastructure.EmployerQuestionRepository;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The employer custom question bank (PRD v2.1 §7.5.8).
 *
 * <p>Optional — the default remains 100% AI-generated — but it is "the most
 * requested control from recruiters who have a house question they always ask".
 * Employer questions occupy the <em>core segment</em> first, so every candidate
 * for a job is asked them and stays comparable, and the AI fills the remainder
 * to reach the tier's count.
 *
 * <h2>The two non-negotiable rules</h2>
 *
 * <p>Both are enforced here rather than trusted to callers:
 *
 * <ol>
 *   <li><strong>Employer questions still pass the safety filter.</strong> A
 *       question is screened on upload and is unusable until it clears. There is
 *       no path in this class that produces an APPROVED question without the
 *       filter having run.</li>
 *   <li><strong>They bypass the quality critic, never the safety filter.</strong>
 *       If an employer wants to ask something the quality critic would score
 *       poorly, that is their call. Safety is not — and there is no override
 *       method here, deliberately.</li>
 * </ol>
 */
@Service
public class EmployerQuestionService {

    private static final Logger log = LoggerFactory.getLogger(EmployerQuestionService.class);

    /**
     * Cap per job. Generous relative to any tier's question count, because
     * §7.5.8 allows more questions than a tier holds — the extras rotate across
     * candidates — but not unbounded.
     */
    private static final int MAX_QUESTIONS_PER_JOB = 100;

    private static final int MAX_QUESTION_LENGTH = 1000;

    private final EmployerQuestionRepository questionRepository;
    private final JobOpeningRepository jobRepository;
    private final QuestionSafetyFilter safetyFilter;

    public EmployerQuestionService(EmployerQuestionRepository questionRepository,
                                   JobOpeningRepository jobRepository,
                                   QuestionSafetyFilter safetyFilter) {
        this.questionRepository = questionRepository;
        this.jobRepository      = jobRepository;
        this.safetyFilter       = safetyFilter;
    }

    /**
     * Adds questions to a job, screening each one.
     *
     * <p>Accepts both upload paths from §7.5.8 — CSV or pasted text, one per
     * line — because by the time they reach here both are just a list of
     * strings.
     *
     * <p>Screening happens synchronously and the result is returned per question,
     * so the employer sees immediately which were refused and why. Deferring it
     * to a worker would leave them looking at a list of PENDING rows with no
     * idea whether their question bank is usable.
     *
     * @return every question as stored, approved and rejected alike
     */
    @Auditable(action = "EMPLOYER_QUESTIONS_UPLOADED", entityType = "JOB", entityIdArg = 0)
    @Transactional
    public List<EmployerQuestion> addQuestions(UUID jobOpeningId, List<String> questionTexts) {
        JobOpening job = requireJob(jobOpeningId);

        List<String> cleaned = questionTexts.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(t -> !t.isBlank())
                .toList();

        if (cleaned.isEmpty()) {
            throw new ValidationException("No questions were supplied.");
        }

        long existing = questionRepository
                .findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(jobOpeningId).size();
        if (existing + cleaned.size() > MAX_QUESTIONS_PER_JOB) {
            throw new ValidationException(
                    "A job may hold at most " + MAX_QUESTIONS_PER_JOB + " custom questions.");
        }

        UUID userId = SecurityContext.requireUserId();
        int order = (int) existing;

        List<EmployerQuestion> saved = new ArrayList<>(cleaned.size());
        for (String text : cleaned) {
            if (text.length() > MAX_QUESTION_LENGTH) {
                throw new ValidationException(
                        "Questions must be " + MAX_QUESTION_LENGTH + " characters or fewer.");
            }

            EmployerQuestion question = new EmployerQuestion();
            question.setCompanyId(job.getCompanyId());
            question.setJobOpeningId(jobOpeningId);
            question.setQuestionText(text);
            question.setDisplayOrder(order++);
            question.setCreatedBy(userId);

            // The safety filter runs on every question, with no way past it.
            QuestionSafetyFilter.Verdict verdict = safetyFilter.screen(text);
            if (verdict.approved()) {
                question.approve();
            } else {
                question.reject(verdict.prohibitedCategory());
                log.info("Employer question refused: jobId={} category={}",
                        jobOpeningId, verdict.prohibitedCategory());
            }

            saved.add(questionRepository.save(question));
        }

        log.info("Employer questions uploaded: jobId={} total={} approved={}",
                jobOpeningId, saved.size(),
                saved.stream().filter(EmployerQuestion::isUsable).count());

        return saved;
    }

    /**
     * Parses pasted text into questions, one per line (§7.5.8).
     *
     * <p>Blank lines and a leading list marker are tolerated — a recruiter
     * pasting from a document will bring "1." or "-" with them, and refusing
     * their paste over formatting would be needless friction.
     */
    public List<String> parsePastedQuestions(String pasted) {
        if (pasted == null || pasted.isBlank()) {
            return List.of();
        }
        return pasted.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .map(line -> line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", ""))
                .filter(line -> !line.isBlank())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployerQuestion> list(UUID jobOpeningId) {
        requireJob(jobOpeningId);
        return questionRepository.findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(jobOpeningId);
    }

    /**
     * The approved set, in employer order — what fills the core segment of a
     * session's question bank.
     *
     * <p>Rejected and unscreened questions are excluded here rather than filtered
     * downstream, so there is exactly one place that decides what is usable.
     */
    @Transactional(readOnly = true)
    public List<EmployerQuestion> approvedFor(UUID jobOpeningId) {
        return questionRepository
                .findAllByJobOpeningIdAndSafetyStatusOrderByDisplayOrderAscCreatedAtAsc(
                        jobOpeningId, QuestionSafetyStatus.APPROVED);
    }

    @Auditable(action = "EMPLOYER_QUESTION_DELETED", entityType = "JOB")
    @Transactional
    public void delete(UUID questionId) {
        UUID companyId = SecurityContext.requireCompanyId();
        EmployerQuestion question = questionRepository.findByCompanyIdAndId(companyId, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("EmployerQuestion", questionId));
        questionRepository.delete(question);
    }

    /**
     * Reorders the bank.
     *
     * <p>Order matters beyond presentation: when an employer supplies more
     * questions than the tier holds, the extras rotate across candidates in this
     * order, so it determines which questions every candidate is guaranteed.
     */
    @Transactional
    public void reorder(UUID jobOpeningId, List<UUID> questionIdsInOrder) {
        requireJob(jobOpeningId);

        List<EmployerQuestion> questions =
                questionRepository.findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(jobOpeningId);

        for (int i = 0; i < questionIdsInOrder.size(); i++) {
            UUID id = questionIdsInOrder.get(i);
            int position = i;
            questions.stream()
                    .filter(q -> q.getId().equals(id))
                    .findFirst()
                    .ifPresent(q -> q.setDisplayOrder(position));
        }
        questionRepository.saveAll(questions);
    }

    private JobOpening requireJob(UUID jobOpeningId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return jobRepository.findByCompanyIdAndId(companyId, jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", jobOpeningId));
    }
}
