package com.interviewengine.session.service;

import com.interviewengine.billing.service.WalletService;
import com.interviewengine.scheduling.service.CapacityService;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionExpiryService} — the per-session transactional
 * expire-and-release worker.
 */
@ExtendWith(MockitoExtension.class)
class SessionExpiryServiceTest {

    @Mock InterviewSessionRepository sessionRepository;
    @Mock WalletService walletService;
    @Mock CapacityService capacityService;

    private SessionExpiryService service() {
        return new SessionExpiryService(sessionRepository, walletService, capacityService);
    }

    private InterviewSession session(UUID id, UUID companyId, SessionStatus status) {
        InterviewSession s = new InterviewSession();
        s.setId(id);
        s.setCompanyId(companyId);
        s.setStatus(status);
        return s;
    }

    @Test
    void invitedSession_isExpiredAndReservationReleased() {
        UUID sessionId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        InterviewSession s = session(sessionId, companyId, SessionStatus.INVITED);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(s));

        boolean acted = service().expireAndRelease(sessionId);

        assertThat(acted).isTrue();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.EXPIRED);
        verify(sessionRepository).save(s);
        verify(walletService).releaseFunds(companyId, sessionId);

        // An INVITED session was never booked into a time slot, so there is no
        // capacity to give back. Releasing anyway would decrement a bucket this
        // session never occupied and hand out a slot twice.
        verifyNoInteractions(capacityService);
    }

    /**
     * §7.4.4 — a candidate who booked a slot and never showed up still has an
     * invite that lapses, and the slot they were holding has to go back.
     *
     * <p>SCHEDULED became expirable in v2.1. Without this path a no-show would
     * permanently consume a capacity bucket: nothing else releases it, so the
     * platform's bookable capacity would ratchet down with every no-show.
     */
    @Test
    void scheduledSession_alsoReleasesTheCapacityBucketItHeld() {
        UUID sessionId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        InterviewSession s = session(sessionId, companyId, SessionStatus.SCHEDULED);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(s));

        boolean acted = service().expireAndRelease(sessionId);

        assertThat(acted).isTrue();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.EXPIRED);
        verify(walletService).releaseFunds(companyId, sessionId);
        verify(capacityService).releaseForSession(sessionId);
    }

    /**
     * The lock's whole purpose. Two pods can both select the same stale invite;
     * the one that loses the race acquires the lock only after the winner has
     * already written EXPIRED. It must not release the reservation a second
     * time — that would credit the company for money it never reserved.
     */
    @Test
    void aSessionAlreadyExpiredByAnotherPodIsNotReleasedTwice() {
        UUID sessionId = UUID.randomUUID();
        InterviewSession alreadyExpired = session(sessionId, UUID.randomUUID(), SessionStatus.EXPIRED);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(alreadyExpired));

        boolean acted = service().expireAndRelease(sessionId);

        assertThat(acted).isFalse();
        verify(sessionRepository, never()).save(alreadyExpired);
        verifyNoInteractions(walletService);
        verifyNoInteractions(capacityService);
    }

    @Test
    void nonInvitedSession_isLeftUntouched() {
        UUID sessionId = UUID.randomUUID();
        InterviewSession started = session(sessionId, UUID.randomUUID(), SessionStatus.IN_PROGRESS);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(started));

        boolean acted = service().expireAndRelease(sessionId);

        assertThat(acted).isFalse();
        assertThat(started.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        verify(sessionRepository, never()).save(started);
        verifyNoInteractions(walletService);
    }

    @Test
    void vanishedSession_isNoOp() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.empty());

        boolean acted = service().expireAndRelease(sessionId);

        assertThat(acted).isFalse();
        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(walletService);
    }
}
