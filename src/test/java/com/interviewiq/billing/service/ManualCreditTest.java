package com.interviewiq.billing.service;

import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.email.service.EmailService;
import com.interviewiq.shared.config.BillingProperties;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.shared.exception.ValidationException;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WalletService#applyManualCredit} (INTIQ-35).
 *
 * <p>This is the only path in the product where someone inside the company
 * creates spendable balance in a customer's account, which is why its
 * properties are pinned down rather than assumed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualCreditTest {

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository txRepository;
    @Mock RazorpayClient razorpayClient;
    @Mock UserRepository userRepository;
    @Mock CompanyRepository companyRepository;
    @Mock EmailService emailService;

    private final RazorpayProperties razorpayProps = new RazorpayProperties();
    private final BillingProperties billingProperties = new BillingProperties();

    private WalletService service() {
        return new WalletService(walletRepository, txRepository, razorpayClient, razorpayProps,
                billingProperties, userRepository, companyRepository, emailService);
    }

    private Wallet wallet(UUID companyId, long balancePaise) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setCompanyId(companyId);
        w.setBalancePaise(balancePaise);
        when(walletRepository.findByCompanyIdForUpdate(companyId)).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    void creditsPaidBalanceAndRecordsTheActingStaffUser() {
        UUID companyId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Wallet w = wallet(companyId, 5_000L);

        service().applyManualCredit(companyId, 10_000L,
                "Refund for interview that failed on our side, ticket 412", staffId);

        assertThat(w.getBalancePaise()).isEqualTo(15_000L);

        ArgumentCaptor<WalletTransaction> saved = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository).save(saved.capture());
        assertThat(saved.getValue().getGrantedByStaffId()).isEqualTo(staffId);
        assertThat(saved.getValue().getGrantReason()).contains("ticket 412");
    }

    /**
     * A correction is not a grant. Promotional credit expires and counts toward
     * the exposure cap; a refund that quietly expired would be a second failure
     * on top of the one being corrected.
     */
    @Test
    void aManualCreditIsPaidBalanceNotPromotionalCredit() {
        UUID companyId = UUID.randomUUID();
        Wallet w = wallet(companyId, 0L);

        service().applyManualCredit(companyId, 20_000L, "Goodwill after the outage on 3 Aug", UUID.randomUUID());

        assertThat(w.getBalancePaise()).isEqualTo(20_000L);
        assertThat(w.getPromoBalancePaise()).isZero();

        ArgumentCaptor<WalletTransaction> saved = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository).save(saved.capture());
        assertThat(saved.getValue().isPromotional()).isFalse();
        assertThat(saved.getValue().getTransactionType()).isEqualTo(TransactionType.TOPUP);
    }

    /**
     * No money changed hands, so there is no taxable supply. Charging GST on a
     * refund would invent a tax liability out of a correction.
     */
    @Test
    void noGstIsChargedOnACorrection() {
        UUID companyId = UUID.randomUUID();
        wallet(companyId, 0L);

        service().applyManualCredit(companyId, 50_000L, "Out-of-band NEFT received 12 Aug", UUID.randomUUID());

        ArgumentCaptor<WalletTransaction> saved = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(txRepository).save(saved.capture());
        assertThat(saved.getValue().getGstPaise()).isZero();
    }

    /**
     * From the customer's point of view the balance just went up. Why it went up
     * does not change when they should next be warned.
     */
    @Test
    void theLowBalanceWarningIsReArmed() {
        UUID companyId = UUID.randomUUID();
        Wallet w = wallet(companyId, 100L);
        w.setLowBalanceNotifiedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        service().applyManualCredit(companyId, 50_000L, "Refund for duplicate charge, ticket 918", UUID.randomUUID());

        assertThat(w.getLowBalanceNotifiedAt()).isNull();
    }

    @Test
    void aBlankReasonIsRefused() {
        UUID companyId = UUID.randomUUID();
        wallet(companyId, 0L);

        assertThatThrownBy(() -> service().applyManualCredit(companyId, 1_000L, "   ", UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reason is required");

        verify(txRepository, never()).save(any());
    }

    @Test
    void aNonPositiveAmountIsRefused() {
        UUID companyId = UUID.randomUUID();
        wallet(companyId, 0L);

        assertThatThrownBy(() -> service().applyManualCredit(companyId, 0L, "Valid enough reason here", UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> service().applyManualCredit(companyId, -500L, "Valid enough reason here", UUID.randomUUID()))
                .isInstanceOf(ValidationException.class);

        verify(txRepository, never()).save(any());
    }
}
