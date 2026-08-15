package com.interviewiq.job.service;

import com.interviewiq.ai.service.QuestionSafetyFilter;
import com.interviewiq.job.domain.EmployerQuestion;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.QuestionSafetyStatus;
import com.interviewiq.job.infrastructure.EmployerQuestionRepository;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.EmployerPrincipal;
import com.interviewiq.shared.security.SecurityContext;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The employer question bank (PRD v2.1 §7.5.8).
 *
 * <p>The rule under test is the one the PRD calls non-negotiable: employer
 * questions bypass the quality critic but never the safety filter, and there is
 * no override.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployerQuestionServiceTest {

    @Mock EmployerQuestionRepository questionRepository;
    @Mock JobOpeningRepository jobRepository;

    private EmployerQuestionService service;

    private UUID companyId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        jobId = UUID.randomUUID();

        // A real safety filter, not a mock — the interaction between the service
        // and the filter is exactly what these tests exist to verify.
        service = new EmployerQuestionService(questionRepository, jobRepository, new QuestionSafetyFilter());

        JobOpening job = new JobOpening();
        job.setId(jobId);
        job.setCompanyId(companyId);
        when(jobRepository.findByCompanyIdAndId(companyId, jobId)).thenReturn(Optional.of(job));
        when(questionRepository.findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(jobId))
                .thenReturn(List.of());
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new EmployerPrincipal(companyId, UUID.randomUUID(), "hr@acme.com",
                                List.of((org.springframework.security.core.GrantedAuthority) () -> "ROLE_ADMIN")),
                        null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // The non-negotiable rule
    // =========================================================================

    @Test
    void aDiscriminatoryQuestionIsRefusedEvenThoughAHumanWroteIt() {
        List<EmployerQuestion> saved = service.addQuestions(jobId,
                List.of("Are you planning to have children?"));

        // "The filter is not optional just because a human wrote the question
        // rather than a model." (§7.5.8)
        assertThat(saved).singleElement()
                .satisfies(q -> {
                    assertThat(q.getSafetyStatus()).isEqualTo(QuestionSafetyStatus.REJECTED);
                    assertThat(q.getRejectionReason()).isEqualTo("marital and family status");
                    assertThat(q.isUsable()).isFalse();
                });
    }

    @Test
    void aLegitimateQuestionIsApprovedAndUsable() {
        List<EmployerQuestion> saved = service.addQuestions(jobId,
                List.of("Describe the hardest production incident you have debugged."));

        assertThat(saved).singleElement()
                .satisfies(q -> {
                    assertThat(q.getSafetyStatus()).isEqualTo(QuestionSafetyStatus.APPROVED);
                    assertThat(q.isUsable()).isTrue();
                });
    }

    @Test
    void aQuestionTheQualityCriticWouldDislikeIsStillAllowed() {
        // "If an employer wants to ask something the quality critic would score
        // poorly, that is their call and we allow it." A vague question is not
        // an unsafe one.
        List<EmployerQuestion> saved = service.addQuestions(jobId, List.of("Tell me about yourself."));

        assertThat(saved).singleElement()
                .extracting(EmployerQuestion::isUsable)
                .isEqualTo(true);
    }

    @Test
    void aMixedUploadApprovesTheGoodAndRefusesTheBad() {
        List<EmployerQuestion> saved = service.addQuestions(jobId, List.of(
                "How would you design a rate limiter?",
                "Are you married?",
                "Walk me through your approach to code review."));

        assertThat(saved).hasSize(3);
        assertThat(saved.stream().filter(EmployerQuestion::isUsable)).hasSize(2);
        assertThat(saved.get(1).getRejectionReason()).isEqualTo("marital and family status");
    }

    @Test
    void noQuestionIsEverStoredApprovedWithoutBeingScreened() {
        List<EmployerQuestion> saved = service.addQuestions(jobId, List.of(
                "What is your caste?",
                "How old are you?",
                "What is your religion?"));

        // There is no path through this service that produces an APPROVED
        // question the filter has not seen.
        assertThat(saved).allMatch(q -> q.getSafetyStatus() != QuestionSafetyStatus.PENDING);
        assertThat(saved).noneMatch(EmployerQuestion::isUsable);
    }

    // =========================================================================
    // Ordering — which questions every candidate is guaranteed
    // =========================================================================

    @Test
    void questionsKeepTheOrderTheyWereSuppliedIn() {
        List<EmployerQuestion> saved = service.addQuestions(jobId,
                List.of("First question here", "Second question here", "Third question here"));

        assertThat(saved).extracting(EmployerQuestion::getDisplayOrder)
                .containsExactly(0, 1, 2);
    }

    // =========================================================================
    // Pasted input
    // =========================================================================

    @Test
    void parsesPastedTextOnePerLine() {
        List<String> parsed = service.parsePastedQuestions("""
                What is your experience with Kafka?
                Describe a time you disagreed with a technical decision.
                """);

        assertThat(parsed).hasSize(2);
    }

    @Test
    void stripsListMarkersARecruiterPastesFromADocument() {
        List<String> parsed = service.parsePastedQuestions("""
                1. What is your experience with Kafka?
                - Describe a design you are proud of.
                • How do you approach testing?
                """);

        assertThat(parsed).containsExactly(
                "What is your experience with Kafka?",
                "Describe a design you are proud of.",
                "How do you approach testing?");
    }

    @Test
    void ignoresBlankLines() {
        assertThat(service.parsePastedQuestions("One question\n\n\nAnother question\n")).hasSize(2);
    }

    @Test
    void handlesEmptyPastedInput() {
        assertThat(service.parsePastedQuestions("")).isEmpty();
        assertThat(service.parsePastedQuestions(null)).isEmpty();
    }

    // =========================================================================
    // Bounds
    // =========================================================================

    @Test
    void refusesAnUploadWithNoUsableInput() {
        assertThatThrownBy(() -> service.addQuestions(jobId, List.of("   ", "")))
                .isInstanceOf(ValidationException.class);
    }
}
