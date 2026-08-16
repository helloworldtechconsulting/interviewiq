package com.interviewengine.session.service;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.session.domain.EvaluationReport;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.ProctoringEvent;
import com.interviewengine.session.domain.QuestionSource;
import com.interviewengine.session.domain.SessionAnswer;
import com.interviewengine.session.infrastructure.EvaluationReportRepository;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.session.infrastructure.ProctoringEventRepository;
import com.interviewengine.session.infrastructure.SessionAnswerRepository;
import com.interviewengine.shared.exception.ResourceNotFoundException;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.shared.security.SecurityContext;
import com.interviewengine.storage.service.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Recruiter-facing downloads for a finished interview: the recording, and the
 * transcript (PRD v2.1 §11).
 *
 * <h2>Why the transcript is generated, not stored</h2>
 *
 * <p>{@code EvaluationReport.transcriptS3Key} has existed since the original
 * schema and has never held a value — the transcript used to be assembled in
 * memory at evaluation time and thrown away. The obvious fix is to start writing
 * a transcript file to object storage and fill the column in. That is the wrong
 * fix.
 *
 * <p>Since {@code V046}, {@code session_answers} holds the authoritative record:
 * one row per question with its text, the candidate's words, whether it was a
 * follow-up, whether it came from the employer's bank, whether it was skipped,
 * and its score. A stored text file would be a lossy copy of that, and copies
 * drift — re-scoring an answer or correcting a transcription would update the
 * rows and leave the file saying something else. It would also create a second
 * object to carry through the retention policy for no benefit.
 *
 * <p>So the transcript is rendered on request from the rows. There is exactly one
 * source of truth, and the download is always current. The dead column is left in
 * place rather than dropped: it costs nothing, and dropping a column is
 * irreversible against production data.
 */
@Service
public class SessionArtifactService {

    /**
     * Recording playback URLs are deliberately short-lived. A recording is video
     * of an identifiable person answering questions about themselves; a URL that
     * outlives the page view it was minted for is a link that can be forwarded
     * out of the product entirely.
     */
    private static final Duration RECORDING_URL_EXPIRY = Duration.ofMinutes(15);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'");

    private final InterviewSessionRepository sessionRepository;
    private final SessionAnswerRepository    answerRepository;
    private final CandidateRepository        candidateRepository;
    private final StorageService             storageService;
    private final ProctoringEventRepository  proctoringRepository;
    private final EvaluationReportRepository reportRepository;

    public SessionArtifactService(InterviewSessionRepository sessionRepository,
                                  SessionAnswerRepository answerRepository,
                                  CandidateRepository candidateRepository,
                                  StorageService storageService,
                                  ProctoringEventRepository proctoringRepository,
                                  EvaluationReportRepository reportRepository) {
        this.sessionRepository    = sessionRepository;
        this.answerRepository     = answerRepository;
        this.candidateRepository  = candidateRepository;
        this.storageService       = storageService;
        this.proctoringRepository = proctoringRepository;
        this.reportRepository     = reportRepository;
    }

    /**
     * Returns a short-lived playback URL for the interview recording.
     *
     * @throws ValidationException when the session has no recording — an interview
     *         the candidate abandoned before the first answer never uploads one,
     *         and that is a normal outcome rather than a fault
     */
    @Transactional(readOnly = true)
    public String recordingUrl(UUID sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (session.getRecordingS3Key() == null) {
            throw new ValidationException(
                    "No recording is available for this interview. "
                            + "Recordings are uploaded when the interview finishes.");
        }
        return storageService.generatePresignedDownloadUrl(session.getRecordingS3Key(), RECORDING_URL_EXPIRY);
    }

    /**
     * Renders the interview transcript as plain text, in the order the questions
     * were actually asked.
     *
     * <p>Skipped questions are included and labelled rather than omitted. A gap in
     * a transcript reads as a technical failure; "no answer given" is information,
     * and it is the information that explains a low score on that question.
     *
     * <p>Employer-sourced questions are marked, for the same reason the report
     * marks them (§7.5.8): someone reading a weak answer needs to know whose
     * question produced it.
     */
    @Transactional(readOnly = true)
    public String transcript(UUID sessionId) {
        InterviewSession session = requireSession(sessionId);
        List<SessionAnswer> answers =
                answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(sessionId);

        if (answers.isEmpty()) {
            throw new ValidationException(
                    "No transcript is available for this interview — no questions were answered.");
        }

        String candidateName = candidateRepository.findById(session.getCandidateId())
                .map(Candidate::getFullName)
                .orElse("Candidate");

        StringBuilder out = new StringBuilder(512);
        out.append("Interview transcript\n")
           .append("Candidate: ").append(candidateName).append('\n');
        if (session.getStartedAt() != null) {
            out.append("Interviewed: ").append(TIMESTAMP.format(session.getStartedAt())).append('\n');
        }
        out.append("Session: ").append(sessionId).append('\n')
           .append("\nThis score is advisory only. A human makes every hiring decision.\n")
           .append("=".repeat(72)).append("\n\n");

        for (SessionAnswer answer : answers) {
            out.append("Q").append(answer.getQuestionIndex() + 1);
            if (answer.isFollowUp()) {
                out.append(" (follow-up)");
            }
            if (answer.getQuestionSource() == QuestionSource.EMPLOYER) {
                out.append(" [your question]");
            }
            out.append('\n').append(answer.getQuestionText()).append("\n\n");

            if (answer.isSkipped() || answer.getTranscriptText() == null || answer.getTranscriptText().isBlank()) {
                out.append("    (no answer given — this question was skipped)\n");
            } else {
                out.append("    ").append(answer.getTranscriptText()).append('\n');
            }

            if (answer.getScore() != null) {
                out.append("\n    Score: ").append(answer.getScore()).append("/10\n");
            }
            out.append('\n').append("-".repeat(72)).append("\n\n");
        }

        return out.toString();
    }

    /** Suggested filename for a transcript download. */
    public String transcriptFilename(UUID sessionId) {
        return "interview-transcript-" + sessionId + ".txt";
    }

    /**
     * Proctoring events for the recruiter, oldest first (§7.5.4, INTIQ-29).
     *
     * <p>Surfaced as a plain list with no summary verdict, and that is
     * deliberate. Proctoring signals are weak individually — a tab switch might
     * be someone checking the time, a camera drop might be a loose cable — and a
     * system that turned them into "suspected cheating" would be making a
     * judgement it has no basis for, about a person, with their job at stake.
     * The recruiter sees what happened and decides what it means.
     */
    @Transactional(readOnly = true)
    public List<ProctoringEvent> proctoringEvents(UUID sessionId) {
        requireSession(sessionId);
        return proctoringRepository.findAllBySessionIdOrderByOccurredAtDesc(sessionId);
    }

    /**
     * Saves the recruiter's private notes on a report.
     *
     * <p>Stored on the report rather than the session because notes are about
     * the assessment, and because the report is what gets read months later when
     * someone asks why a decision was made. Never sent to a model: these are the
     * recruiter's own words about a candidate, and feeding them back into
     * scoring would let an opinion formed after the interview influence the
     * evaluation of it.
     */
    @Transactional
    public void saveEmployerNotes(UUID sessionId, String notes) {
        UUID companyId = SecurityContext.requireCompanyId();
        EvaluationReport report = reportRepository.findByCompanyIdAndSessionId(companyId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("EvaluationReport for session", sessionId));

        report.setEmployerNotes(notes == null || notes.isBlank() ? null : notes.strip());
        reportRepository.save(report);
    }

    private InterviewSession requireSession(UUID sessionId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return sessionRepository.findByCompanyIdAndId(companyId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }
}
