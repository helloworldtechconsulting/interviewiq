package com.interviewiq.session.scheduler;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.session.service.SessionExpiryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionExpiryJob} — the paged fetch-then-release loop that
 * replaced the fund-stranding bulk UPDATE.
 *
 * <p>Verifies the job delegates one release per stale session, drains across pages,
 * isolates per-session failures, and cannot spin forever when every row fails.
 */
@ExtendWith(MockitoExtension.class)
class SessionExpiryJobTest {

    @Mock InterviewSessionRepository sessionRepository;
    @Mock SessionExpiryService sessionExpiryService;

    private SessionExpiryJob job() {
        return new SessionExpiryJob(sessionRepository, sessionExpiryService);
    }

    private InterviewSession sessionWithId(UUID id) {
        InterviewSession s = new InterviewSession();
        s.setId(id);
        s.setStatus(SessionStatus.INVITED);
        return s;
    }

    private Page<InterviewSession> pageOf(InterviewSession... sessions) {
        return new PageImpl<>(List.of(sessions));
    }

    @Test
    void expiresEveryStaleSession_thenStopsWhenPageEmpty() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();

        when(sessionRepository.findByStatusInAndInviteExpiresAtBefore(
                anyCollection(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(pageOf(sessionWithId(s1), sessionWithId(s2)))  // first round
                .thenReturn(pageOf());                                     // drained

        when(sessionExpiryService.expireAndRelease(any(UUID.class))).thenReturn(true);

        job().expireStaleInvites();

        verify(sessionExpiryService).expireAndRelease(s1);
        verify(sessionExpiryService).expireAndRelease(s2);
        verifyNoMoreInteractions(sessionExpiryService);
    }

    @Test
    void continuesPastAFailingSession() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();

        when(sessionRepository.findByStatusInAndInviteExpiresAtBefore(
                anyCollection(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(pageOf(sessionWithId(bad), sessionWithId(good)))
                .thenReturn(pageOf());

        when(sessionExpiryService.expireAndRelease(bad))
                .thenThrow(new RuntimeException("optimistic lock clash"));
        when(sessionExpiryService.expireAndRelease(good)).thenReturn(true);

        job().expireStaleInvites();

        // Both attempted; the good one still processed despite the bad one throwing.
        verify(sessionExpiryService).expireAndRelease(bad);
        verify(sessionExpiryService).expireAndRelease(good);
    }

    @Test
    void abortsInsteadOfLoopingWhenEveryRowFails() {
        // A full-throughput page where every session throws => no forward progress.
        // The job must abort after one round rather than re-fetch the same rows forever.
        when(sessionRepository.findByStatusInAndInviteExpiresAtBefore(
                anyCollection(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(pageOf(sessionWithId(UUID.randomUUID())));

        when(sessionExpiryService.expireAndRelease(any(UUID.class)))
                .thenThrow(new RuntimeException("boom"));

        job().expireStaleInvites();

        // Repository queried exactly once — the loop broke on zero progress.
        verify(sessionRepository, times(1)).findByStatusInAndInviteExpiresAtBefore(
                anyCollection(), any(OffsetDateTime.class), any(Pageable.class));
    }

    @Test
    void noStaleSessions_doesNothing() {
        when(sessionRepository.findByStatusInAndInviteExpiresAtBefore(
                anyCollection(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(pageOf());

        job().expireStaleInvites();

        assertThat(true).isTrue();
        verifyNoMoreInteractions(sessionExpiryService);
    }
}
