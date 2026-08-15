package com.interviewiq.session.service;

import com.interviewiq.billing.service.WalletService;
import com.interviewiq.scheduling.service.CapacityService;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
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
