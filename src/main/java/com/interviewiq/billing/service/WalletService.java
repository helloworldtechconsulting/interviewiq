package com.interviewiq.billing.service;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.dto.TopUpResponse;
import com.interviewiq.billing.dto.WalletResponse;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.shared.exception.ExternalServiceException;
import com.interviewiq.shared.exception.InsufficientBalanceException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.security.SecurityContext;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Wallet lifecycle service — balance queries, top-up initiation, and the internal
 * reserve / settle / release operations used by SessionService.
 *
 * <h2>Balance model</h2>
 * <ul>
 *   <li>{@code balancePaise} — total credited funds</li>
 *   <li>{@code reservedPaise} — ring-fenced for in-progress sessions</li>
 *   <li>available = {@code balancePaise - reservedPaise}</li>
 * </ul>
 *
 * <h2>Optimistic locking</h2>
 * <p>All balance mutations go through {@link Wallet}'s {@code @Version} field.
 * Concurrent updates on the wallet row surface as optimistic locking failures
 * and will propagate as 409 Conflict to the caller.
 *
 * <h2>Top-up flow</h2>
 * <ol>
 *   <li>Client calls {@link #initiateTopUp} → creates a Razorpay order, returns order ID + key.</li>
 *   <li>Frontend opens Razorpay checkout; payment goes to Razorpay directly.</li>
 *   <li>Razorpay posts a {@code payment.captured} webhook → {@link #confirmTopUp} credits the wallet.</li>
 * </ol>
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository            walletRepository;
    private final WalletTransactionRepository txRepository;
    private final RazorpayClient              razorpayClient;
    private final RazorpayProperties          razorpayProps;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository txRepository,
                         RazorpayClient razorpayClient,
                         RazorpayProperties razorpayProps) {
        this.walletRepository = walletRepository;
        this.txRepository     = txRepository;
        this.razorpayClient   = razorpayClient;
        this.razorpayProps    = razorpayProps;
    }

    // =========================================================================
    // Query operations
    // =========================================================================

    /**
     * Returns the authenticated company's wallet balance.
     */
    @Transactional(readOnly = true)
    public WalletResponse getBalance() {
        UUID companyId = SecurityContext.requireCompanyId();
        return WalletResponse.from(requireWalletByCompanyId(companyId));
    }

    /**
     * Returns paginated transaction history for the authenticated company.
     */
    @Transactional(readOnly = true)
    public Page<WalletTransaction> getTransactions(Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        Wallet wallet = requireWalletByCompanyId(companyId);
        return txRepository.findAllByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }

    // =========================================================================
    // Top-up initiation (public surface)
    // =========================================================================

    /**
     * Creates a Razorpay order for the requested amount and returns the order ID
     * for the frontend Razorpay checkout widget.
     *
     * <p>No wallet mutation happens here — credit is applied only after the
     * Razorpay webhook confirms payment capture.
     *
     * @param amountPaise amount to top up (must be ≥ 5000 paise = ₹50)
     */
    @Transactional
    public TopUpResponse initiateTopUp(long amountPaise) {
        UUID companyId = SecurityContext.requireCompanyId();
        // Load wallet to confirm it exists — we do not mutate it yet
        requireWalletByCompanyId(companyId);

        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountPaise);           // paise
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "topup-" + companyId);
            orderRequest.put("payment_capture", 1);            // auto-capture

            Order order = razorpayClient.orders.create(orderRequest);
            String orderId = order.get("id");

            log.info("Razorpay order created: companyId={} orderId={} amountPaise={}",
                    companyId, orderId, amountPaise);

            return new TopUpResponse(orderId, amountPaise, "INR", razorpayProps.getKeyId());

        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed: companyId={} error={}", companyId, e.getMessage());
            throw new ExternalServiceException("Payment gateway error. Please try again later.");
        }
    }

    // =========================================================================
    // Webhook-driven confirmation (called by WebhookService)
    // =========================================================================

    /**
     * Credits the wallet after Razorpay confirms a payment capture.
     * Idempotent: if this order ID has already been credited, does nothing.
     *
     * @param razorpayOrderId the Razorpay order ID from the webhook payload
     * @param amountPaise     the captured amount from the webhook
     * @param companyId       resolved from the order receipt prefix
     */
    @Transactional
    public void confirmTopUp(String razorpayOrderId, long amountPaise, UUID companyId) {
        // Idempotency: reject if already credited
        if (txRepository.findByRazorpayOrderId(razorpayOrderId).isPresent()) {
            log.info("TopUp already credited, skipping: orderId={}", razorpayOrderId);
            return;
        }

        Wallet wallet = requireWalletByCompanyId(companyId);
        wallet.setBalancePaise(wallet.getBalancePaise() + amountPaise);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, null, TransactionType.TOPUP,
                amountPaise, wallet.getBalancePaise());
        tx.setRazorpayOrderId(razorpayOrderId);
        txRepository.save(tx);

        log.info("Wallet topped up: companyId={} orderId={} amountPaise={} newBalance={}",
                companyId, razorpayOrderId, amountPaise, wallet.getBalancePaise());
    }

    // =========================================================================
    // Internal billing operations (used by SessionService)
    // =========================================================================

    /**
     * Ring-fences {@code amountPaise} from the available balance when a session is
     * <em>created</em> (INVITED state) — not when it later starts. The reservation is
     * therefore outstanding for the whole invite window and must be released if the
     * session is cancelled, fails, or expires unaccepted.
     * Throws {@link InsufficientBalanceException} if available funds are insufficient.
     */
    @Transactional
    public WalletTransaction reserveFunds(UUID companyId, UUID sessionId, long amountPaise) {
        Wallet wallet = requireWalletByCompanyId(companyId);
        long available = wallet.getBalancePaise() - wallet.getReservedPaise();

        if (available < amountPaise) {
            throw new InsufficientBalanceException(amountPaise, available);
        }

        wallet.setReservedPaise(wallet.getReservedPaise() + amountPaise);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, sessionId, TransactionType.RESERVATION,
                amountPaise, wallet.getBalancePaise());
        tx.setStatus(TransactionStatus.PENDING);
        return txRepository.save(tx);
    }

    /**
     * Finalises the session charge: reduces both {@code balancePaise} and {@code reservedPaise}
     * by the settled amount (which may differ from the reservation if billed by actual duration).
     * Marks the RESERVATION transaction as CONFIRMED.
     *
     * @param companyId       company owning the wallet
     * @param sessionId       session that completed
     * @param settledPaise    actual amount to charge (≤ reservation amount)
     */
    @Transactional
    public void settleFunds(UUID companyId, UUID sessionId, long settledPaise) {
        Wallet wallet = requireWalletByCompanyId(companyId);

        // Find the pending reservation for this session
        WalletTransaction reservation = txRepository
                .findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                        wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING)
                .orElse(null);

        long reservedAmount = reservation != null ? reservation.getAmountPaise() : settledPaise;

        // Release the full reservation, charge the settled amount
        wallet.setReservedPaise(Math.max(0, wallet.getReservedPaise() - reservedAmount));
        wallet.setBalancePaise(Math.max(0, wallet.getBalancePaise() - settledPaise));
        walletRepository.save(wallet);

        if (reservation != null) {
            reservation.setStatus(TransactionStatus.CONFIRMED);
            txRepository.save(reservation);
        }

        // Record the settlement
        txRepository.save(buildTransaction(wallet, sessionId, TransactionType.SETTLEMENT,
                settledPaise, wallet.getBalancePaise()));

        log.info("Funds settled: companyId={} sessionId={} settledPaise={}", companyId, sessionId, settledPaise);
    }

    /**
     * Returns the full reservation when a session is cancelled before starting.
     */
    @Transactional
    public void releaseFunds(UUID companyId, UUID sessionId) {
        Wallet wallet = requireWalletByCompanyId(companyId);

        WalletTransaction reservation = txRepository
                .findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                        wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING)
                .orElse(null);

        if (reservation == null) {
            log.warn("No pending reservation to release: companyId={} sessionId={}", companyId, sessionId);
            return;
        }

        long releasedAmount = reservation.getAmountPaise();
        wallet.setReservedPaise(Math.max(0, wallet.getReservedPaise() - releasedAmount));
        walletRepository.save(wallet);

        reservation.setStatus(TransactionStatus.RELEASED);
        txRepository.save(reservation);

        txRepository.save(buildTransaction(wallet, sessionId, TransactionType.RELEASE,
                releasedAmount, wallet.getBalancePaise()));

        log.info("Funds released: companyId={} sessionId={} releasedPaise={}", companyId, sessionId, releasedAmount);
    }

    // =========================================================================
    // Package-visible helpers (used by CompanyService, SessionService)
    // =========================================================================

    public Wallet requireWalletByCompanyId(UUID companyId) {
        return walletRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for company: " + companyId));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private WalletTransaction buildTransaction(Wallet wallet, UUID sessionId,
                                               TransactionType type, long amountPaise,
                                               long balanceAfterPaise) {
        WalletTransaction tx = new WalletTransaction();
        tx.setCompanyId(wallet.getCompanyId());
        tx.setWalletId(wallet.getId());
        tx.setSessionId(sessionId);
        tx.setTransactionType(type);
        tx.setAmountPaise(amountPaise);
        tx.setBalanceAfterPaise(balanceAfterPaise);
        tx.setStatus(TransactionStatus.CONFIRMED);
        return tx;
    }
}
