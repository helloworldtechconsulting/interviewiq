package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.TransactionStatus;
import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.Wallet;
import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletRepository;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.shared.config.BillingProperties;
import com.interviewengine.shared.config.RazorpayProperties;
import com.interviewengine.shared.exception.ConflictException;
import com.interviewengine.shared.exception.InsufficientBalanceException;
import com.interviewengine.shared.exception.ValidationException;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Promotional credit behaviour (PRD v2.1 §7.8.3).
 *
 * <p>The spend-ordering rule is the one the PRD states most forcefully:
 * "Promotional credit FIRST, paid balance second. Never the reverse. A customer
 * must never see paid money consumed while free credit sits unused — that is a
 * refund request and a trust problem."
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionalCreditTest {

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository txRepository;
    @Mock RazorpayClient razorpayClient;
    @Mock RazorpayProperties razorpayProps;

    @Spy BillingProperties billingProperties = new BillingProperties();

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
        wallet.setBalancePaise(0L);
        wallet.setPromoBalancePaise(0L);
        wallet.setReservedPaise(0L);

        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(wallet));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // Spend ordering — promotional first, always
    // =========================================================================

    @Test
    void settlementSpendsPromotionalCreditBeforePaidBalance() {
        wallet.setPaidAndPromo(50_000L, 30_000L);   // ₹500 paid, ₹300 promo
        wallet.setReservedPaise(10_000L);
        givenPendingReservation(10_000L);

        walletService.settleFunds(companyId, sessionId, 10_000L);   // one ₹100 interview

        // The whole charge came out of promotional credit; paid balance untouched.
        assertThat(wallet.getPromoBalancePaise()).isEqualTo(20_000L);
        assertThat(wallet.getBalancePaise()).isEqualTo(50_000L);
    }

    @Test
    void settlementFallsThroughToPaidBalanceOnlyOncePromoIsExhausted() {
        wallet.setPaidAndPromo(50_000L, 4_000L);    // ₹500 paid, ₹40 promo
        wallet.setReservedPaise(10_000L);
        givenPendingReservation(10_000L);

        walletService.settleFunds(companyId, sessionId, 10_000L);

        // ₹40 of promo consumed entirely, the remaining ₹60 from paid balance.
        assertThat(wallet.getPromoBalancePaise()).isZero();
        assertThat(wallet.getBalancePaise()).isEqualTo(44_000L);
    }

    @Test
    void paidBalanceIsNeverTouchedWhilePromoWouldCover() {
        wallet.setPaidAndPromo(100L, 30_000L);      // almost no paid money, plenty of promo
        wallet.setReservedPaise(10_000L);
        givenPendingReservation(10_000L);

        walletService.settleFunds(companyId, sessionId, 10_000L);

        assertThat(wallet.getBalancePaise()).isEqualTo(100L);
    }

    // =========================================================================
    // Reservations draw on the combined balance
    // =========================================================================

    @Test
    void aSessionCanBeReservedEntirelyAgainstPromotionalCredit() {
        wallet.setPaidAndPromo(0L, 30_000L);        // free trial only — no paid money at all

        walletService.reserveFunds(companyId, sessionId, 10_000L);

        // This is the whole point of the signup grant: a company that has paid
        // nothing can still run interviews.
        assertThat(wallet.getReservedPaise()).isEqualTo(10_000L);
    }

    @Test
    void reservationIsRefusedWhenTheCombinedBalanceIsShort() {
        wallet.setPaidAndPromo(3_000L, 4_000L);     // ₹70 combined against a ₹100 interview

        assertThatThrownBy(() -> walletService.reserveFunds(companyId, sessionId, 10_000L))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    // =========================================================================
    // Settlement idempotency (§7.8.1)
    // =========================================================================

    @Test
    void aSessionIsNeverChargedTwice() {
        wallet.setPaidAndPromo(50_000L, 0L);
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(txRepository.existsByWalletIdAndSessionIdAndTransactionType(
                wallet.getId(), sessionId, TransactionType.SETTLEMENT)).thenReturn(true);

        walletService.settleFunds(companyId, sessionId, 10_000L);

        // "With multiple pods running, the settlement write must be guarded ...
        // or a session can be double-charged." Nothing moved.
        assertThat(wallet.getBalancePaise()).isEqualTo(50_000L);
    }

    // =========================================================================
    // Grants
    // =========================================================================

    @Test
    void aGrantWithoutAReasonIsRefused() {
        assertThatThrownBy(() ->
                walletService.grantPromotionalCredit(companyId, 30_000L, "  ", null, UUID.randomUUID()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aGrantCreditsThePromotionalBalanceAndNotThePaidOne() {
        walletService.grantPromotionalCredit(
                companyId, 30_000L, "Signup grant", null, UUID.randomUUID());

        assertThat(wallet.getPromoBalancePaise()).isEqualTo(30_000L);
        assertThat(wallet.getBalancePaise()).isZero();
    }

    @Test
    void aGrantNeverBearsGst() {
        UUID staffId = UUID.randomUUID();

        walletService.grantPromotionalCredit(companyId, 30_000L, "Signup grant", null, staffId);

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository).save(captor.capture());
        WalletTransaction tx = captor.getValue();

        // Promotional credit is not a sale. "If free credit appears on an
        // invoice, the accounting and the tax filing disagree with each other."
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.PROMO_CREDIT);
        assertThat(tx.getGstPaise()).isZero();
        assertThat(tx.isPromotional()).isTrue();
        assertThat(tx.getTransactionType().isInvoiceable()).isFalse();
        assertThat(tx.getGrantReason()).isEqualTo("Signup grant");
        assertThat(tx.getGrantedByStaffId()).isEqualTo(staffId);
    }

    @Test
    void aGrantIsRefusedOnceTheExposureCapIsReached() {
        billingProperties.getSignupGrant().setTotalExposureCapPaise(100_000L);
        when(walletRepository.totalPromotionalExposurePaise()).thenReturn(95_000L);

        assertThatThrownBy(() ->
                walletService.grantPromotionalCredit(companyId, 30_000L, "Big grant", null, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Expiry
    // =========================================================================

    @Test
    void expiryReversesOnlyTheUnspentRemainder() {
        wallet.setPaidAndPromo(0L, 8_000L);   // ₹300 granted, ₹220 already spent

        walletService.expirePromotionalCredit(companyId, 30_000L, UUID.randomUUID());

        // Cannot claw back credit the company already used.
        assertThat(wallet.getPromoBalancePaise()).isZero();
    }

    @Test
    void expiryWritesAReversingEntryRatherThanAdjustingSilently() {
        wallet.setPaidAndPromo(0L, 30_000L);

        walletService.expirePromotionalCredit(companyId, 30_000L, UUID.randomUUID());

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository).save(captor.capture());

        // The ledger has to explain the balance. A silent adjustment leaves the
        // customer looking at a number that dropped for no visible reason.
        assertThat(captor.getValue().getTransactionType()).isEqualTo(TransactionType.PROMO_EXPIRY);
        assertThat(captor.getValue().getAmountPaise()).isEqualTo(30_000L);
    }

    @Test
    void expiringAnAlreadySpentGrantIsANoOp() {
        wallet.setPaidAndPromo(0L, 0L);

        walletService.expirePromotionalCredit(companyId, 30_000L, UUID.randomUUID());

        assertThat(wallet.getPromoBalancePaise()).isZero();
        verify(txRepository, org.mockito.Mockito.never()).save(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void givenPendingReservation(long amountPaise) {
        WalletTransaction reservation = new WalletTransaction();
        reservation.setAmountPaise(amountPaise);
        reservation.setStatus(TransactionStatus.PENDING);
        when(txRepository.findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING))
                .thenReturn(Optional.of(reservation));
    }
}
