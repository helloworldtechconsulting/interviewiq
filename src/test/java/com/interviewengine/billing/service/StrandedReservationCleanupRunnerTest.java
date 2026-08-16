package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.TransactionStatus;
import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StrandedReservationCleanupRunner} — the flag-gated one-off
 * sweep that releases reservations stranded by the pre-fix expiry job.
 */
@ExtendWith(MockitoExtension.class)
class StrandedReservationCleanupRunnerTest {

    @Mock WalletTransactionRepository txRepository;
    @Mock WalletService walletService;

    private StrandedReservationCleanupRunner runner(boolean enabled) {
        return new StrandedReservationCleanupRunner(txRepository, walletService, enabled);
    }

    private WalletTransaction reservation(UUID id, UUID companyId, UUID sessionId) {
        WalletTransaction tx = new WalletTransaction();
        tx.setId(id);
        tx.setCompanyId(companyId);
        tx.setSessionId(sessionId);
        tx.setTransactionType(TransactionType.RESERVATION);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmountPaise(5000L);
        return tx;
    }

    private Page<WalletTransaction> pageOf(WalletTransaction... txns) {
        return new PageImpl<>(List.of(txns));
    }

    @Test
    void disabled_doesNothing() {
        runner(false).run(null);
        verifyNoInteractions(txRepository, walletService);
    }

    @Test
    void enabled_releasesEveryStrandedReservation_thenStops() {
        UUID companyId = UUID.randomUUID();
        WalletTransaction r1 = reservation(UUID.randomUUID(), companyId, UUID.randomUUID());
        WalletTransaction r2 = reservation(UUID.randomUUID(), companyId, UUID.randomUUID());

        when(txRepository.findReservationsForSessionsInStatus(
                eq(TransactionType.RESERVATION), eq(TransactionStatus.PENDING),
                eq(SessionStatus.EXPIRED), any(Pageable.class)))
                .thenReturn(pageOf(r1, r2))   // first round
                .thenReturn(pageOf());        // drained

        runner(true).run(null);

        verify(walletService).releaseFunds(companyId, r1.getSessionId());
        verify(walletService).releaseFunds(companyId, r2.getSessionId());
    }

    @Test
    void terminatesWhenReleaseDoesNotDrainRow() {
        // Simulate the pathological case that motivated the processed-id guard:
        // releaseFunds is a no-op (void) that never flips the row, so page 0 keeps
        // returning the same reservation. The runner must NOT loop forever.
        WalletTransaction stuck = reservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        when(txRepository.findReservationsForSessionsInStatus(
                any(TransactionType.class), any(TransactionStatus.class),
                any(SessionStatus.class), any(Pageable.class)))
                .thenReturn(pageOf(stuck));   // ALWAYS the same row

        runner(true).run(null);   // returns => no infinite loop

        // Attempted exactly once; the second fetch sees only already-processed ids and breaks.
        verify(walletService, times(1)).releaseFunds(stuck.getCompanyId(), stuck.getSessionId());
    }
}
