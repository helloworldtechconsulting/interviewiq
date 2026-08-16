package com.interviewengine.session.scheduler;

import com.interviewengine.auth.domain.User;
import com.interviewengine.auth.infrastructure.UserRepository;
import com.interviewengine.billing.service.WalletService;
import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.email.service.EmailService;
import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.scheduling.service.CapacityService;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.shared.config.SchedulingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NoShowSweep} — the sweep that finally notices a candidate did
 * not turn up and gives the ₹100 back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoShowSweepTest {

    @Mock InterviewSessionRepository sessionRepository;
    @Mock CandidateRepository        candidateRepository;
    @Mock JobOpeningRepository       jobOpeningRepository;
    @Mock UserRepository             userRepository;
    @Mock WalletService              walletService;
    @Mock CapacityService            capacityService;
    @Mock EmailService               emailService;

    private final SchedulingProperties props = new SchedulingProperties();

    private NoShowSweep sweep() {
        return new NoShowSweep(sessionRepository, candidateRepository, jobOpeningRepository,
                userRepository, walletService, capacityService, emailService, props);
    }

    private InterviewSession session() {
        InterviewSession s = new InterviewSession();
        s.setId(UUID.randomUUID());
        s.setCompanyId(UUID.randomUUID());
        s.setCandidateId(UUID.randomUUID());
        s.setJobOpeningId(UUID.randomUUID());
        s.setStatus(SessionStatus.NO_SHOW);
        s.setScheduledStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        return s;
    }

    private void wireRecruiter(InterviewSession s) {
        JobOpening job = new JobOpening();
        job.setId(s.getJobOpeningId());
        job.setTitle("Senior Java Engineer");
        job.setCreatedBy(UUID.randomUUID());
        when(jobOpeningRepository.findById(s.getJobOpeningId())).thenReturn(Optional.of(job));

        User recruiter = new User();
        recruiter.setEmail("recruiter@acme.com");
        when(userRepository.findById(job.getCreatedBy())).thenReturn(Optional.of(recruiter));

        Candidate c = new Candidate();
        c.setFullName("Priya Nair");
        when(candidateRepository.findById(s.getCandidateId())).thenReturn(Optional.of(c));
    }

    @Test
    void releasesTheReservationAndTheCapacityForEachNoShow() {
        InterviewSession s = session();
        wireRecruiter(s);
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of(s));

        sweep().sweep();

        verify(walletService).releaseFunds(s.getCompanyId(), s.getId());
        verify(capacityService).releaseForSession(s.getId());
    }

    /**
     * §7.4.5 — a no-show is not charged. No interview happened and no evaluation
     * ran, so settling would bill for something that does not exist.
     */
    @Test
    void neverSettlesANoShow() {
        InterviewSession s = session();
        wireRecruiter(s);
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of(s));

        sweep().sweep();

        verify(walletService, never()).settleFunds(any(), any(), anyLong());
    }

    @Test
    void tellsTheRecruiterWhoOwnsTheOpening() {
        InterviewSession s = session();
        wireRecruiter(s);
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of(s));

        sweep().sweep();

        verify(emailService).sendNoShowNoticeEmail(
                eq("recruiter@acme.com"), eq("Priya Nair"), eq("Senior Java Engineer"), any(), eq(s.getCompanyId()));
    }

    /**
     * The grace window is what stands between a candidate stuck in traffic and a
     * cancelled interview, so the cutoff must be behind now by exactly the
     * configured grace — never ahead of it.
     */
    @Test
    void claimsOnlySessionsPastTheGraceWindow() {
        props.setNoShowGrace(Duration.ofMinutes(15));
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        sweep().sweep();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepository).claimNoShows(cutoff.capture(), anyInt());

        assertThat(cutoff.getValue()).isBefore(before);
        assertThat(cutoff.getValue()).isAfter(before.minusMinutes(16));
    }

    @Test
    void doesNothingWhenThereAreNoNoShows() {
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of());

        sweep().sweep();

        verifyNoInteractions(walletService, capacityService, emailService);
    }

    /**
     * One bad session must not abort the batch — the others are each holding
     * their own ₹100.
     */
    @Test
    void oneFailingReleaseDoesNotStopTheRest() {
        InterviewSession bad  = session();
        InterviewSession good = session();
        wireRecruiter(good);
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("wallet locked"))
                .when(walletService).releaseFunds(bad.getCompanyId(), bad.getId());

        sweep().sweep();

        verify(walletService).releaseFunds(good.getCompanyId(), good.getId());
        verify(capacityService, times(1)).releaseForSession(good.getId());
    }

    /**
     * A failed notification must not make a successful release look like a
     * failure — the money being back is what matters.
     */
    @Test
    void aFailedEmailDoesNotUndoTheRelease() {
        InterviewSession s = session();
        wireRecruiter(s);
        when(sessionRepository.claimNoShows(any(), anyInt())).thenReturn(List.of(s));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendNoShowNoticeEmail(anyString(), anyString(), anyString(), any(), any());

        sweep().sweep();

        verify(walletService).releaseFunds(s.getCompanyId(), s.getId());
        verify(capacityService).releaseForSession(s.getId());
    }
}
