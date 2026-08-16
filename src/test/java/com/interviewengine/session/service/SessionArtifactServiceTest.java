package com.interviewengine.session.service;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.QuestionSource;
import com.interviewengine.session.domain.SessionAnswer;
import com.interviewengine.session.infrastructure.EvaluationReportRepository;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.session.infrastructure.ProctoringEventRepository;
import com.interviewengine.session.infrastructure.SessionAnswerRepository;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.shared.security.EmployerPrincipal;
import com.interviewengine.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionArtifactService} — recording playback and transcript
 * rendering.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionArtifactServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock InterviewSessionRepository sessionRepository;
    @Mock SessionAnswerRepository    answerRepository;
    @Mock CandidateRepository        candidateRepository;
    @Mock StorageService             storageService;
    @Mock ProctoringEventRepository  proctoringRepository;
    @Mock EvaluationReportRepository reportRepository;

    private SessionArtifactService service() {
        return new SessionArtifactService(
                sessionRepository, answerRepository, candidateRepository, storageService,
                proctoringRepository, reportRepository);
    }

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new EmployerPrincipal(COMPANY_ID, UUID.randomUUID(), "r@example.com", List.of()),
                        null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private InterviewSession session(String recordingKey) {
        InterviewSession s = new InterviewSession();
        s.setId(SESSION_ID);
        s.setCompanyId(COMPANY_ID);
        s.setCandidateId(UUID.randomUUID());
        s.setRecordingS3Key(recordingKey);
        when(sessionRepository.findByCompanyIdAndId(COMPANY_ID, SESSION_ID)).thenReturn(Optional.of(s));
        when(candidateRepository.findById(s.getCandidateId())).thenReturn(Optional.of(candidate()));
        return s;
    }

    private Candidate candidate() {
        Candidate c = new Candidate();
        c.setFullName("Ravi Krishnan");
        return c;
    }

    private SessionAnswer answer(int index, String question, String transcript,
                                 boolean skipped, boolean followUp, QuestionSource source) {
        SessionAnswer a = new SessionAnswer();
        a.setSessionId(SESSION_ID);
        a.setCompanyId(COMPANY_ID);
        a.setQuestionIndex(index);
        a.setQuestionText(question);
        a.setTranscriptText(transcript);
        a.setSkipped(skipped);
        a.setFollowUp(followUp);
        a.setQuestionSource(source);
        return a;
    }

    // =========================================================================
    // Recording
    // =========================================================================

    @Test
    void recordingUrlIsPresignedAndShortLived() {
        session("recordings/c/s/file.webm");
        when(storageService.generatePresignedDownloadUrl(eq("recordings/c/s/file.webm"), any()))
                .thenReturn("https://storage.example/signed");

        assertThat(service().recordingUrl(SESSION_ID)).isEqualTo("https://storage.example/signed");
    }

    /**
     * A candidate who abandons the interview before answering never uploads a
     * recording. That is a normal outcome, so the caller gets a clear message
     * rather than a signed URL pointing at nothing.
     */
    @Test
    void missingRecordingIsRefusedRatherThanSignedAnyway() {
        session(null);

        assertThatThrownBy(() -> service().recordingUrl(SESSION_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No recording is available");

        verifyNoInteractions(storageService);
    }

    // =========================================================================
    // Transcript
    // =========================================================================

    @Test
    void transcriptRendersEveryQuestionInOrderWithTheCandidateName() {
        session(null);
        when(answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(SESSION_ID))
                .thenReturn(List.of(
                        answer(0, "Describe a race condition you have fixed.",
                                "I used a synchronized block on the counter.", false, false, QuestionSource.AI),
                        answer(1, "What did volatile alone not give you?",
                                "It only guarantees visibility, not atomicity.", false, true, QuestionSource.AI)));

        String out = service().transcript(SESSION_ID);

        assertThat(out).contains("Ravi Krishnan");
        assertThat(out).contains("Describe a race condition you have fixed.");
        assertThat(out).contains("I used a synchronized block on the counter.");
        assertThat(out.indexOf("Q1")).isLessThan(out.indexOf("Q2"));
        assertThat(out).contains("Q2 (follow-up)");
    }

    /**
     * A skipped question is labelled, not dropped. An unexplained gap reads as a
     * technical fault; "no answer given" is the fact that explains the score.
     */
    @Test
    void skippedQuestionsAreLabelledRatherThanOmitted() {
        session(null);
        when(answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(SESSION_ID))
                .thenReturn(List.of(
                        answer(0, "Tell me about a deadline that slipped.", null, true, false, QuestionSource.AI)));

        String out = service().transcript(SESSION_ID);

        assertThat(out).contains("Tell me about a deadline that slipped.");
        assertThat(out).contains("no answer given");
    }

    /** §7.5.8 — a reader must be able to tell whose question produced an answer. */
    @Test
    void employerSourcedQuestionsAreMarked() {
        session(null);
        when(answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(SESSION_ID))
                .thenReturn(List.of(
                        answer(0, "Why do you want to work here?", "Because of the product.",
                                false, false, QuestionSource.EMPLOYER)));

        assertThat(service().transcript(SESSION_ID)).contains("[your question]");
    }

    /** §7.10 — the advisory-only guarantee travels with the document, not just the UI. */
    @Test
    void transcriptCarriesTheAdvisoryOnlyDisclaimer() {
        session(null);
        when(answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(SESSION_ID))
                .thenReturn(List.of(answer(0, "Q", "A", false, false, QuestionSource.AI)));

        assertThat(service().transcript(SESSION_ID)).contains("advisory only");
    }

    @Test
    void anInterviewWithNoAnswersHasNoTranscript() {
        session(null);
        when(answerRepository.findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(SESSION_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().transcript(SESSION_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no questions were answered");
    }
}
