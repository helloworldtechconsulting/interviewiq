package com.interviewiq.candidate.service;

import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.dto.UpdateCandidateRequest;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.security.EmployerPrincipal;
import com.interviewiq.storage.service.StorageObjectRecorder;
import com.interviewiq.storage.service.StorageService;
import com.interviewiq.storage.service.UploadKeyService;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the edit and delete guards on {@link CandidateService}.
 *
 * <p>These guards are the whole story of INTIQ-19 — the CRUD around them is
 * unremarkable, but each refusal prevents a specific bad outcome, and it is the
 * refusals that are worth pinning down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CandidateLifecycleGuardTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();

    @Mock CandidateRepository        candidateRepository;
    @Mock JobOpeningRepository       jobOpeningRepository;
    @Mock StorageService             storageService;
    @Mock UploadKeyService           uploadKeyService;
    @Mock StorageObjectRecorder      storageObjectRecorder;
    @Mock InterviewSessionRepository sessionRepository;

    private CandidateService service() {
        return new CandidateService(candidateRepository, jobOpeningRepository, storageService,
                uploadKeyService, storageObjectRecorder, sessionRepository);
    }

    @BeforeEach
    void authenticateAsEmployer() {
        EmployerPrincipal principal = new EmployerPrincipal(
                COMPANY_ID, UUID.randomUUID(), "recruiter@example.com", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Candidate candidate(UUID id) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setCompanyId(COMPANY_ID);
        c.setJobOpeningId(UUID.randomUUID());
        c.setEmail("aisha@example.com");
        c.setFullName("Aisha Menon");
        when(candidateRepository.findByCompanyIdAndId(COMPANY_ID, id)).thenReturn(Optional.of(c));
        return c;
    }

    // =========================================================================
    // Update
    // =========================================================================

    @Test
    void updateSucceedsWhileTheCandidateHasNeverBeenInvited() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id);
        when(sessionRepository.existsByCandidateId(id)).thenReturn(false);

        service().update(id, new UpdateCandidateRequest("Aisha M Menon", null, "+91 98765 43210"));

        assertThat(c.getFullName()).isEqualTo("Aisha M Menon");
        assertThat(c.getPhone()).isEqualTo("+91 98765 43210");
        verify(candidateRepository).save(c);
    }

    @Test
    void updateIsRefusedOnceAnInviteHasBeenSent() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id);
        when(sessionRepository.existsByCandidateId(id)).thenReturn(true);

        assertThatThrownBy(() -> service().update(id, new UpdateCandidateRequest("New Name", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been invited");

        assertThat(c.getFullName()).isEqualTo("Aisha Menon");
        verify(candidateRepository, never()).save(any());
    }

    @Test
    void updateRefusesAnEmailAlreadyUsedInTheSameOpening() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id);
        when(sessionRepository.existsByCandidateId(id)).thenReturn(false);
        when(candidateRepository.existsByJobOpeningIdAndEmail(c.getJobOpeningId(), "taken@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().update(id,
                new UpdateCandidateRequest(null, "taken@example.com", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updatingToTheSameEmailIsNotTreatedAsADuplicate() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id);
        when(sessionRepository.existsByCandidateId(id)).thenReturn(false);

        // Same address, differently cased — the uniqueness check must not fire
        // against the candidate's own row.
        service().update(id, new UpdateCandidateRequest(null, "Aisha@Example.com", null));

        assertThat(c.getEmail()).isEqualTo("aisha@example.com");
        verify(candidateRepository, never())
                .existsByJobOpeningIdAndEmail(any(), eq("aisha@example.com"));
    }

    // =========================================================================
    // Delete
    // =========================================================================

    @Test
    void deleteSucceedsWhenThereIsNoInterviewHistory() {
        UUID id = UUID.randomUUID();
        Candidate c = candidate(id);
        when(sessionRepository.existsByCandidateIdAndStatus(id, SessionStatus.COMPLETED)).thenReturn(false);
        when(sessionRepository.existsByCandidateIdAndStatusIn(eq(id), any())).thenReturn(false);

        service().delete(id);

        verify(candidateRepository).delete(c);
    }

    @Test
    void deleteIsRefusedWhenACompletedInterviewExists() {
        UUID id = UUID.randomUUID();
        candidate(id);
        when(sessionRepository.existsByCandidateIdAndStatus(id, SessionStatus.COMPLETED)).thenReturn(true);

        assertThatThrownBy(() -> service().delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("evaluation report");

        verify(candidateRepository, never()).delete(any());
    }

    /**
     * The refusal that is not in the story but matters more day to day: an
     * in-flight session holds a ₹100 reservation, and deleting the candidate would
     * leave nothing pointing at it to release it.
     */
    @Test
    void deleteIsRefusedWhileASessionStillHoldsAReservation() {
        UUID id = UUID.randomUUID();
        candidate(id);
        when(sessionRepository.existsByCandidateIdAndStatus(id, SessionStatus.COMPLETED)).thenReturn(false);
        when(sessionRepository.existsByCandidateIdAndStatusIn(eq(id), any())).thenReturn(true);

        assertThatThrownBy(() -> service().delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cancel it first");

        verify(candidateRepository, never()).delete(any());
    }

    /**
     * Guards against the in-flight list quietly losing a state. Every state that
     * holds a reservation or capacity must be in it; terminal states must not be,
     * or a candidate whose interview expired could never be deleted.
     */
    @Test
    void theInFlightStateListCoversExactlyTheNonTerminalStates() {
        UUID id = UUID.randomUUID();
        candidate(id);
        when(sessionRepository.existsByCandidateIdAndStatus(id, SessionStatus.COMPLETED)).thenReturn(false);

        @SuppressWarnings("unchecked")
        Collection<SessionStatus>[] captured = new Collection[1];
        when(sessionRepository.existsByCandidateIdAndStatusIn(eq(id), any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(1);
            return false;
        });

        service().delete(id);

        assertThat(captured[0])
                .containsExactlyInAnyOrder(
                        SessionStatus.INVITED,
                        SessionStatus.SCHEDULED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.EVALUATING)
                .allSatisfy(s -> assertThat(s.isTerminal()).isFalse());
    }
}
