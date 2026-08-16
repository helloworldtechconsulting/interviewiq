package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.TransactionStatus;
import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.Wallet;
import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletRepository;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.shared.config.BillingProperties;
import com.interviewengine.shared.config.RazorpayProperties;
import com.interviewengine.shared.exception.InsufficientBalanceException;
import com.interviewengine.shared.exception.ResourceNotFoundException;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WalletService} — reserve / settle / release state machine
 * and confirmTopUp idempotency.
 *
 * <p>All external dependencies are mocked; no Spring context or database is required.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository txRepository;
    @Mock RazorpayClient razorpayClient;
    @Mock RazorpayProperties razorpayProps;

    @Spy  BillingProperties billingProperties = new BillingProperties();

    @InjectMocks WalletService walletService;

    private UUID companyId;
    private UUID sessionId;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setCompanyId(companyId);
        wallet.setBalancePaise(10_000L);   // ₹100
        wallet.setReservedPaise(0L);
        wallet.setVersion(0L);
    }

    // =========================================================================
    // reserveFunds
    // =========================================================================

    @Test
    void reserveFunds_happyPath_incrementsReservedPaise() {
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletTransaction tx = walletService.reserveFunds(companyId, sessionId, 5_000L);

        // Wallet reservation was incremented
        verify(walletRepository).save(wallet);
        assertThat(wallet.getReservedPaise()).isEqualTo(5_000L);

        // Transaction type and status are correct
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.RESERVATION);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getAmountPaise()).isEqualTo(5_000L);
    }

    @Test
    void reserveFunds_throwsInsufficientBalance_whenAvailableFundsInsufficient() {
        wallet.setBalancePaise(3_000L);    // only ₹30 available
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.reserveFunds(companyId, sessionId, 5_000L))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(walletRepository, never()).save(any());
        verify(txRepository, never()).save(any());
    }

    @Test
    void reserveFunds_throwsInsufficientBalance_whenBalanceFullyReserved() {
        wallet.setBalancePaise(10_000L);
        wallet.setReservedPaise(8_000L);   // only ₹20 available
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.reserveFunds(companyId, sessionId, 5_000L))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    // =========================================================================
    // settleFunds
    // =========================================================================

    @Test
    void settleFunds_reducesBalanceAndReleasesReservation() {
        wallet.setBalancePaise(10_000L);
        wallet.setReservedPaise(5_000L);

        WalletTransaction pending = new WalletTransaction();
        pending.setId(UUID.randomUUID());
        pending.setWalletId(wallet.getId());
        pending.setSessionId(sessionId);
        pending.setTransactionType(TransactionType.RESERVATION);
        pending.setStatus(TransactionStatus.PENDING);
        pending.setAmountPaise(5_000L);
        pending.setBalanceAfterPaise(10_000L);

        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.settleFunds(companyId, sessionId, 5_000L);

        assertThat(wallet.getReservedPaise()).isEqualTo(0L);
        assertThat(wallet.getBalancePaise()).isEqualTo(5_000L);
        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
    }

    @Test
    void settleFunds_guardsAgainstNegativeBalance_withMathMax() {
        // If settled amount somehow exceeds balance (data inconsistency), result must be 0, not negative
        wallet.setBalancePaise(2_000L);
        wallet.setReservedPaise(5_000L);

        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.settleFunds(companyId, sessionId, 5_000L);

        assertThat(wallet.getBalancePaise()).isGreaterThanOrEqualTo(0L);
        assertThat(wallet.getReservedPaise()).isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // releaseFunds
    // =========================================================================

    @Test
    void releaseFunds_resetsReservationAndPersistsReleaseTransaction() {
        wallet.setReservedPaise(5_000L);

        WalletTransaction pending = new WalletTransaction();
        pending.setId(UUID.randomUUID());
        pending.setWalletId(wallet.getId());
        pending.setAmountPaise(5_000L);
        pending.setBalanceAfterPaise(10_000L);
        pending.setTransactionType(TransactionType.RESERVATION);
        pending.setStatus(TransactionStatus.PENDING);

        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.releaseFunds(companyId, sessionId);

        assertThat(wallet.getReservedPaise()).isEqualTo(0L);
        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.RELEASED);

        // Two saves: the reservation update + the RELEASE transaction record
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(t -> t.getTransactionType() == TransactionType.RELEASE);
    }

    @Test
    void releaseFunds_isNoOp_whenNoPendingReservation() {
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        walletService.releaseFunds(companyId, sessionId); // must not throw

        verify(walletRepository, never()).save(any());
    }

    // =========================================================================
    // confirmTopUp (idempotency)
    // =========================================================================

    @Test
    void confirmTopUp_creditsWallet_onFirstCall() {
        String orderId = "order_test_001";
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.empty());
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        walletService.confirmTopUp(orderId, 20_000L, companyId);

        assertThat(wallet.getBalancePaise()).isEqualTo(30_000L); // 10_000 + 20_000
        verify(walletRepository).save(wallet);
    }

    @Test
    void confirmTopUp_isIdempotent_whenOrderAlreadyCredited() {
        String orderId = "order_test_001";
        WalletTransaction existing = new WalletTransaction();
        when(txRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.of(existing));

        walletService.confirmTopUp(orderId, 20_000L, companyId);

        // No wallet lookup, no save — pure no-op
        verify(walletRepository, never()).findByCompanyIdForUpdate(any());
        verify(walletRepository, never()).save(any());
    }

    // =========================================================================
    // requireWalletByCompanyId
    // =========================================================================

    @Test
    void requireWalletByCompanyId_throwsResourceNotFound_whenAbsent() {
        when(walletRepository.findByCompanyId(companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.requireWalletByCompanyId(companyId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
