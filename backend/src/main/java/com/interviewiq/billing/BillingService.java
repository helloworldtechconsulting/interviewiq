package com.interviewiq.billing;

import com.interviewiq.auth.Company;
import com.interviewiq.auth.CompanyRepository;
import com.interviewiq.billing.dto.TopupOrderResponse;
import com.interviewiq.billing.dto.WalletResponse;
import com.interviewiq.billing.dto.WalletTransactionResponse;
import com.interviewiq.common.BadRequestException;
import com.interviewiq.common.ResourceNotFoundException;
import com.interviewiq.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final WalletTransactionRepository transactionRepository;
    private final CompanyRepository companyRepository;
    private final EmailService emailService;

    @Value("${app.interview.cost-per-interview-paise:10000}")
    private long costPerInterviewPaise;

    @Value("${razorpay.api-key:}")
    private String razorpayApiKey;

    @Value("${razorpay.api-secret:}")
    private String razorpayApiSecret;

    @Value("${razorpay.webhook-secret:}")
    private String razorpayWebhookSecret;

    @Transactional
    public void reserveFundsForInterview(UUID sessionId, UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        if (company.getWalletBalancePaise() < costPerInterviewPaise) {
            throw new BadRequestException("Insufficient wallet balance. Please top up your account.");
        }

        long newBalance = company.getWalletBalancePaise() - costPerInterviewPaise;
        company.setWalletBalancePaise(newBalance);
        companyRepository.save(company);

        WalletTransaction transaction = WalletTransaction.builder()
                .companyId(companyId)
                .sessionId(sessionId)
                .type(TransactionType.HOLD)
                .amountPaise(costPerInterviewPaise)
                .balanceAfterPaise(newBalance)
                .description("Reserved for interview session: " + sessionId)
                .build();

        transactionRepository.save(transaction);

        log.info("Funds reserved for interview: {} on company: {}", sessionId, companyId);
    }

    @Transactional
    public void confirmInterviewDebit(UUID sessionId, UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        WalletTransaction transaction = WalletTransaction.builder()
                .companyId(companyId)
                .sessionId(sessionId)
                .type(TransactionType.DEBIT)
                .amountPaise(costPerInterviewPaise)
                .balanceAfterPaise(company.getWalletBalancePaise())
                .description("Interview session completed: " + sessionId)
                .build();

        transactionRepository.save(transaction);

        log.info("Interview debit confirmed: {} on company: {}", sessionId, companyId);
    }

    @Transactional
    public void releaseFunds(UUID sessionId, UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        long newBalance = company.getWalletBalancePaise() + costPerInterviewPaise;
        company.setWalletBalancePaise(newBalance);
        companyRepository.save(company);

        WalletTransaction transaction = WalletTransaction.builder()
                .companyId(companyId)
                .sessionId(sessionId)
                .type(TransactionType.RELEASE)
                .amountPaise(costPerInterviewPaise)
                .balanceAfterPaise(newBalance)
                .description("Funds released for cancelled interview: " + sessionId)
                .build();

        transactionRepository.save(transaction);

        log.info("Funds released for cancelled interview: {} on company: {}", sessionId, companyId);
    }

    @Transactional
    public void creditWallet(UUID companyId, long amountPaise, String razorpayPaymentId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        long newBalance = company.getWalletBalancePaise() + amountPaise;
        company.setWalletBalancePaise(newBalance);
        companyRepository.save(company);

        WalletTransaction transaction = WalletTransaction.builder()
                .companyId(companyId)
                .type(TransactionType.CREDIT)
                .amountPaise(amountPaise)
                .razorpayPaymentId(razorpayPaymentId)
                .balanceAfterPaise(newBalance)
                .description("Wallet top-up via Razorpay")
                .build();

        transactionRepository.save(transaction);

        emailService.sendPaymentReceipt(company.getDomain(), company.getName(), amountPaise, razorpayPaymentId);

        log.info("Wallet credited for company: {} with amount: {}", companyId, amountPaise);
    }

    public long getWalletBalance(UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));
        return company.getWalletBalancePaise();
    }

    public List<WalletTransaction> getTransactionHistory(UUID companyId) {
        return transactionRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    public WalletResponse getBalance(UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));
        long balancePaise = company.getWalletBalancePaise();
        double balanceRupees = balancePaise / 100.0;
        return new WalletResponse(balancePaise, balanceRupees);
    }

    @Transactional
    public TopupOrderResponse initiateTopup(UUID companyId, long amountPaise) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        if (amountPaise < 10000) {
            throw new BadRequestException("Minimum top-up amount is 100 rupees (10000 paise)");
        }

        // Generate Razorpay order (mocked implementation)
        String orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        log.info("Initiated topup order: {} for company: {} with amount: {}", orderId, companyId, amountPaise);

        return new TopupOrderResponse(orderId, amountPaise, "INR", razorpayApiKey);
    }

    @Transactional
    public WalletResponse verifyAndCreditTopup(UUID companyId, String razorpayOrderId,
                                                String razorpayPaymentId, String razorpaySignature) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found"));

        // Verify Razorpay signature
        if (!verifyRazorpaySignature(razorpayOrderId, razorpayPaymentId, razorpaySignature)) {
            throw new BadRequestException("Invalid payment signature. Payment verification failed.");
        }

        // Mock: extract amount from payment (in real implementation, fetch from Razorpay)
        long amountPaise = 50000; // Example: 500 rupees

        // Credit the wallet
        long newBalance = company.getWalletBalancePaise() + amountPaise;
        company.setWalletBalancePaise(newBalance);
        companyRepository.save(company);

        WalletTransaction transaction = WalletTransaction.builder()
                .companyId(companyId)
                .type(TransactionType.CREDIT)
                .amountPaise(amountPaise)
                .razorpayPaymentId(razorpayPaymentId)
                .balanceAfterPaise(newBalance)
                .description("Wallet top-up via Razorpay - Order: " + razorpayOrderId)
                .build();

        transactionRepository.save(transaction);

        emailService.sendPaymentReceipt(company.getDomain(), company.getName(), amountPaise, razorpayPaymentId);

        log.info("Topup verified and wallet credited for company: {} with amount: {}", companyId, amountPaise);

        double balanceRupees = newBalance / 100.0;
        return new WalletResponse(newBalance, balanceRupees);
    }

    public Page<WalletTransactionResponse> getTransactions(UUID companyId, Pageable pageable) {
        return transactionRepository.findByCompanyId(companyId, pageable)
                .map(txn -> new WalletTransactionResponse(
                        txn.getId(),
                        txn.getType(),
                        txn.getAmountPaise(),
                        txn.getDescription(),
                        txn.getBalanceAfterPaise(),
                        txn.getCreatedAt()
                ));
    }

    private boolean verifyRazorpaySignature(String orderId, String paymentId, String signature) {
        try {
            String payload = orderId + "|" + paymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(razorpayApiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String calculatedSignature = Base64.getEncoder().encodeToString(hash);
            return calculatedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Error verifying Razorpay signature: {}", e.getMessage());
            return false;
        }
    }
}
