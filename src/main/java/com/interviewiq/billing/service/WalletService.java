package com.interviewiq.billing.service;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.dto.TopUpResponse;
import com.interviewiq.billing.dto.WalletResponse;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.email.service.EmailService;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.shared.config.BillingProperties;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.exception.ValidationException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final BillingProperties           billingProperties;
    private final UserRepository              userRepository;
    private final CompanyRepository           companyRepository;
    private final EmailService                emailService;

    @Value("${app.frontend.base-url:https://app.interviewiq.in}")
    private String frontendBaseUrl;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository txRepository,
                         RazorpayClient razorpayClient,
                         RazorpayProperties razorpayProps,
                         BillingProperties billingProperties,
                         UserRepository userRepository,
                         CompanyRepository companyRepository,
                         EmailService emailService) {
        this.walletRepository  = walletRepository;
        this.txRepository      = txRepository;
        this.razorpayClient    = razorpayClient;
        this.razorpayProps     = razorpayProps;
        this.billingProperties = billingProperties;
        this.userRepository    = userRepository;
        this.companyRepository = companyRepository;
        this.emailService      = emailService;
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
        Wallet wallet = requireWalletByCompanyId(companyId);

        // The dashboard shows "Balance ₹700 (₹200 promotional, expires 30 Sep)",
        // so the earliest outstanding grant expiry travels with the balance
        // (§7.7). A customer must never be surprised about which money is being
        // spent, or about when free credit disappears.
        OffsetDateTime promoExpiresAt = wallet.getPromoBalancePaise() > 0
                ? txRepository.earliestOutstandingGrantExpiry(companyId)
                : null;

        return WalletResponse.from(wallet, promoExpiresAt,
                billingProperties.getLowBalanceThresholdPaise());
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

    /**
     * Credits the wallet from the browser's checkout callback, after verifying
     * Razorpay's signature (INTIQ-66).
     *
     * <p><strong>Why this exists when the webhook already credits.</strong> The
     * webhook is authoritative but asynchronous — it can arrive seconds or
     * minutes after the customer's payment succeeds. Until then the customer is
     * looking at a balance that has not moved, having just been charged. That is
     * the moment people email support, or pay twice.
     *
     * <p>This does not replace the webhook. It races it, and whichever arrives
     * first credits the wallet; the other becomes a no-op because
     * {@link #confirmTopUp} is idempotent on the Razorpay order id. Removing the
     * webhook and trusting only this would be wrong — a customer who closes the
     * tab during redirect would never be credited at all.
     *
     * <p><strong>The signature check is the whole security of this endpoint.</strong>
     * The payload comes from the browser, so without verification any
     * authenticated user could POST an arbitrary order id and mint themselves
     * credit. Razorpay signs {@code order_id|payment_id} with the key secret;
     * only someone holding that secret can produce a valid signature. Verified
     * with a constant-time comparison, and refused outright when no secret is
     * configured — the same fail-closed rule as the webhook (INTIQ-34), for the
     * same reason.
     *
     * @throws ValidationException if the signature does not verify
     */
    @Transactional
    public WalletResponse verifyAndCreditTopUp(String razorpayOrderId,
                                               String razorpayPaymentId,
                                               String razorpaySignature) {
        UUID companyId = SecurityContext.requireCompanyId();

        String secret = razorpayProps.getKeySecret();
        if (secret == null || secret.isBlank()) {
            log.error("Top-up verification refused: Razorpay key secret is not configured");
            throw new ValidationException("Payment verification is unavailable. Please contact support.");
        }
        if (razorpayOrderId == null || razorpayPaymentId == null || razorpaySignature == null) {
            throw new ValidationException("Payment confirmation is incomplete.");
        }

        String expected = hmacSha256Hex(razorpayOrderId + "|" + razorpayPaymentId, secret);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                razorpaySignature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Top-up verification failed: signature mismatch companyId={} orderId={}",
                    companyId, razorpayOrderId);
            throw new ValidationException("This payment could not be verified.");
        }

        // The amount is taken from the order we created, never from the request.
        // A verified signature proves the payment is genuine; it does not prove
        // the caller told us the right amount.
        long amountPaise = fetchOrderAmount(razorpayOrderId);

        confirmTopUp(razorpayOrderId, amountPaise, companyId);
        return getBalance();
    }

    /** Reads the authoritative order amount back from Razorpay. */
    private long fetchOrderAmount(String razorpayOrderId) {
        try {
            Order order = razorpayClient.orders.fetch(razorpayOrderId);
            return ((Number) order.get("amount")).longValue();
        } catch (RazorpayException e) {
            log.error("Could not fetch Razorpay order for verification: orderId={}", razorpayOrderId, e);
            throw new ExternalServiceException("Payment gateway error. Your payment will be credited shortly.");
        }
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
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

        Wallet wallet = lockWallet(companyId);
        wallet.setBalancePaise(wallet.getBalancePaise() + amountPaise);
        // Re-arm the low-balance warning in the same write. A company that tops
        // up and later runs down again should be warned again; without clearing
        // this they would be told once, ever.
        wallet.setLowBalanceNotifiedAt(null);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, null, TransactionType.TOPUP,
                amountPaise, wallet.getTotalBalancePaise());
        tx.setRazorpayOrderId(razorpayOrderId);
        // Paid top-ups bear GST and are the only transactions that appear on an
        // invoice (§7.8.3). Promotional credit never reaches this path.
        tx.setGstPaise(billingProperties.gstOn(amountPaise));
        txRepository.save(tx);

        log.info("Wallet topped up: companyId={} orderId={} amountPaise={} newBalance={}",
                companyId, razorpayOrderId, amountPaise, wallet.getBalancePaise());
    }

    // =========================================================================
    // Internal billing operations (used by SessionService)
    // =========================================================================

    /**
     * Ring-fences {@code amountPaise} when a session is <em>created</em> (INVITED
     * state) — not when it later starts. The reservation is therefore outstanding
     * for the whole invite window and must be released if the session is
     * cancelled, fails, or expires unaccepted.
     *
     * <p>Checked against the <strong>combined</strong> paid and promotional
     * balance. Which pot actually pays is decided at settlement by the
     * promotional-first ordering (§7.8.3) — a reservation is a claim on the total,
     * not on a particular pot, because between reserving and settling a company
     * may top up or a grant may expire.
     *
     * <p>The wallet is loaded under a row lock: reading the balance and then
     * writing an increased reservation is a read-modify-write, and unlocked, two
     * concurrent session creations can both see enough funds and both reserve.
     *
     * @throws InsufficientBalanceException if the combined available balance will not cover it
     */
    @Transactional
    public WalletTransaction reserveFunds(UUID companyId, UUID sessionId, long amountPaise) {
        Wallet wallet = lockWallet(companyId);
        long available = wallet.getAvailablePaise();

        if (available < amountPaise) {
            throw new InsufficientBalanceException(amountPaise, available);
        }

        wallet.setReservedPaise(wallet.getReservedPaise() + amountPaise);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, sessionId, TransactionType.RESERVATION,
                amountPaise, wallet.getTotalBalancePaise());
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
        Wallet wallet = lockWallet(companyId);

        // Find the pending reservation for this session
        WalletTransaction reservation = txRepository
                .findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
                        wallet.getId(), sessionId, TransactionType.RESERVATION, TransactionStatus.PENDING)
                .orElse(null);

        if (reservation == null && txRepository.existsByWalletIdAndSessionIdAndTransactionType(
                wallet.getId(), sessionId, TransactionType.SETTLEMENT)) {
            // Already settled. §7.8.1 requires settlement to be idempotent per
            // session: with several pods running, a retried completion must not
            // charge the company twice.
            log.info("Session already settled, skipping: companyId={} sessionId={}", companyId, sessionId);
            return;
        }

        long reservedAmount = reservation != null ? reservation.getAmountPaise() : settledPaise;

        // Release the full reservation, then charge — promotional credit first.
        wallet.setReservedPaise(Math.max(0, wallet.getReservedPaise() - reservedAmount));
        Spend spend = spendPromotionalFirst(wallet, settledPaise);
        walletRepository.save(wallet);

        if (reservation != null) {
            reservation.setStatus(TransactionStatus.CONFIRMED);
            txRepository.save(reservation);
        }

        WalletTransaction settlement = buildTransaction(wallet, sessionId, TransactionType.SETTLEMENT,
                settledPaise, wallet.getTotalBalancePaise());
        txRepository.save(settlement);

        log.info("Funds settled: companyId={} sessionId={} settledPaise={} fromPromo={} fromPaid={}",
                companyId, sessionId, settledPaise, spend.fromPromo(), spend.fromPaid());

        maybeWarnLowBalance(wallet);
    }

    /**
     * Sends the low-balance warning if the wallet has just dropped to or below
     * the threshold and has not already been warned (§7.7, §7.8.2, INTIQ-71).
     *
     * <p>Checked on settlement rather than on a schedule, because settlement is
     * the only event that reduces the balance — a sweep would find the same
     * companies repeatedly and tell them nothing new.
     *
     * <p><strong>Warn once per top-up cycle.</strong> The stamp is set here and
     * cleared on top-up. Without it, a company sitting below the line would be
     * emailed after every completed interview, and a warning that arrives on
     * every event is a warning people filter — precisely when it matters most.
     */
    private void maybeWarnLowBalance(Wallet wallet) {
        long remaining = wallet.getTotalBalancePaise();
        if (remaining > billingProperties.getLowBalanceThresholdPaise()) {
            return;
        }
        if (wallet.getLowBalanceNotifiedAt() != null) {
            return;
        }

        try {
            String recipient = adminEmailFor(wallet.getCompanyId());
            if (recipient == null) {
                return;
            }
            String companyName = companyRepository.findById(wallet.getCompanyId())
                    .map(Company::getName)
                    .orElse("Your company");

            emailService.sendLowBalanceEmail(
                    recipient, companyName, remaining,
                    remaining / billingProperties.getSessionCostPaise(),
                    frontendBaseUrl + "/billing",
                    wallet.getCompanyId());

            wallet.setLowBalanceNotifiedAt(OffsetDateTime.now(ZoneOffset.UTC));
            walletRepository.save(wallet);

        } catch (RuntimeException e) {
            // Never fails the settlement. The interview is complete and the
            // charge is correct; a missed warning email is not worth unwinding
            // a billing transaction over.
            log.warn("Low-balance notification failed: companyId={}", wallet.getCompanyId(), e);
        }
    }

    /** First ADMIN on the company, which is who signed up and holds the card. */
    private String adminEmailFor(UUID companyId) {
        return userRepository.findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(companyId, UserRole.ADMIN)
                .map(User::getEmail)
                .orElse(null);
    }

    /**
     * Deducts an amount, spending promotional credit before paid balance.
     *
     * <p><strong>Never the reverse.</strong> PRD v2.1 §7.8.3 is unusually direct:
     * "A customer must never see paid money consumed while free credit sits
     * unused — that is a refund request and a trust problem."
     *
     * <p>Mutates the wallet in place; the caller persists it.
     *
     * @return how the charge was split, for the log and the transaction record
     */
    private Spend spendPromotionalFirst(Wallet wallet, long amountPaise) {
        long fromPromo = Math.min(wallet.getPromoBalancePaise(), amountPaise);
        long fromPaid  = amountPaise - fromPromo;

        wallet.setPromoBalancePaise(wallet.getPromoBalancePaise() - fromPromo);
        wallet.setBalancePaise(Math.max(0, wallet.getBalancePaise() - fromPaid));

        return new Spend(fromPromo, fromPaid);
    }

    /** How a charge divided between promotional and paid balance. */
    private record Spend(long fromPromo, long fromPaid) {}

    /**
     * Returns the full reservation when a session is cancelled before starting.
     */
    @Transactional
    public void releaseFunds(UUID companyId, UUID sessionId) {
        Wallet wallet = lockWallet(companyId);

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
                releasedAmount, wallet.getTotalBalancePaise()));

        log.info("Funds released: companyId={} sessionId={} releasedPaise={}", companyId, sessionId, releasedAmount);
    }


    // =========================================================================
    // Promotional credits (PRD v2.1 §7.8.3)
    // =========================================================================

    /**
     * Grants promotional credit to a company.
     *
     * <p><strong>Staff-only.</strong> §7.1.3 requires that "the grant endpoint is
     * restricted to the internal console role, requires a reason, and writes an
     * AuditLog row. No employer-facing path can create a PROMO_CREDIT
     * transaction." The role check lives on the controller; the mandatory reason
     * is enforced here and again by a database CHECK, because an unexplained
     * free-credit entry makes promotional exposure unauditable.
     *
     * <p>Promotional credit lands in a balance of its own — it is not a sale, so
     * it never bears GST and never appears on an invoice. Keeping it separate is
     * what stops the accounting and the tax filing disagreeing with each other.
     *
     * @param companyId      recipient
     * @param amountPaise    how much free credit to grant
     * @param reason         mandatory justification, recorded on the transaction
     * @param expiresAt      optional expiry; swept by {@code PromoCreditExpiryJob}
     * @param grantedByStaffId the staff user making the grant
     */
    @Auditable(action = "PROMO_CREDIT_GRANTED", entityType = "COMPANY", entityIdArg = 0)
    @Transactional
    public WalletTransaction grantPromotionalCredit(UUID companyId,
                                                    long amountPaise,
                                                    String reason,
                                                    OffsetDateTime expiresAt,
                                                    UUID grantedByStaffId) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("A reason is required for every promotional grant.");
        }
        if (amountPaise <= 0) {
            throw new ValidationException("Promotional grant amount must be positive.");
        }

        long cap = billingProperties.getSignupGrant().getTotalExposureCapPaise();
        if (cap > 0) {
            long exposure = walletRepository.totalPromotionalExposurePaise();
            if (exposure + amountPaise > cap) {
                // "Grants are capped and monitored" (§7.8.3). Refusing here is
                // preferable to discovering the exposure in a month-end report.
                throw new ConflictException(
                        "This grant would exceed the platform promotional exposure cap.");
            }
        }

        Wallet wallet = lockWallet(companyId);
        wallet.setPromoBalancePaise(wallet.getPromoBalancePaise() + amountPaise);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, null, TransactionType.PROMO_CREDIT,
                amountPaise, wallet.getTotalBalancePaise());
        tx.setPromotional(true);
        tx.setGrantReason(reason.strip());
        tx.setExpiresAt(expiresAt);
        tx.setGrantedByStaffId(grantedByStaffId);
        // No GST: promotional credit is not a sale (§7.8.3, §8 Tax).
        tx.setGstPaise(0L);
        txRepository.save(tx);

        log.info("Promotional credit granted: companyId={} amountPaise={} expiresAt={} byStaff={} reason={}",
                companyId, amountPaise, expiresAt, grantedByStaffId, reason);
        return tx;
    }

    /**
     * Reverses promotional credit that expired unspent.
     *
     * <p>Written as a reversing transaction rather than a silent balance
     * adjustment, so the balance and the ledger stay consistent and the
     * disappearance of credit is explainable to the customer (§7.8.3).
     *
     * <p>Reverses only what is still there: if the company already spent most of
     * the grant, only the unspent remainder can lapse.
     */
    @Transactional
    public void expirePromotionalCredit(UUID companyId, long grantedPaise, UUID grantTransactionId) {
        Wallet wallet = lockWallet(companyId);

        long reversible = Math.min(wallet.getPromoBalancePaise(), grantedPaise);
        if (reversible <= 0) {
            log.debug("Expired promotional grant was already spent: companyId={} grantId={}",
                    companyId, grantTransactionId);
            return;
        }

        wallet.setPromoBalancePaise(wallet.getPromoBalancePaise() - reversible);
        walletRepository.save(wallet);

        WalletTransaction tx = buildTransaction(wallet, null, TransactionType.PROMO_EXPIRY,
                reversible, wallet.getTotalBalancePaise());
        tx.setPromotional(true);
        tx.setGstPaise(0L);
        tx.setDescription("Promotional credit expired");
        txRepository.save(tx);

        log.info("Promotional credit expired: companyId={} reversedPaise={} grantId={}",
                companyId, reversible, grantTransactionId);
    }

    /**
     * Applies the self-serve signup grant, once per company.
     *
     * <p>Called on email verification (§7.1.1). Returns quietly rather than
     * throwing when the grant does not apply — a company that already has one, or
     * a disabled grant, is a normal outcome of verification, not an error that
     * should fail the user's signup.
     *
     * <p>The one-per-company check is the last of the abuse guards, not the only
     * one: §7.8.3 also requires email-domain deduplication with stricter
     * treatment of public free-mail domains, and payment-instrument
     * deduplication. Those sit in front of this call, in
     * {@code PromotionalGrantService}.
     *
     * @return true if a grant was applied
     */
    @Transactional
    public boolean applySignupGrant(UUID companyId, UUID grantedByStaffId) {
        BillingProperties.SignupGrant config = billingProperties.getSignupGrant();
        if (!config.isEnabled() || config.getAmountPaise() <= 0) {
            return false;
        }

        OffsetDateTime expiresAt = config.getValidFor() == null
                ? null
                : OffsetDateTime.now(ZoneOffset.UTC).plus(config.getValidFor());

        grantPromotionalCredit(companyId, config.getAmountPaise(),
                "Self-serve signup grant", expiresAt, grantedByStaffId);
        return true;
    }

    // =========================================================================
    // Package-visible helpers (used by CompanyService, SessionService)
    // =========================================================================

    public Wallet requireWalletByCompanyId(UUID companyId) {
        return walletRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for company: " + companyId));
    }

    /**
     * Loads the wallet under a row lock. Every path that moves money uses this
     * rather than {@link #requireWalletByCompanyId} — see
     * {@code WalletRepository.findByCompanyIdForUpdate} for why.
     */
    private Wallet lockWallet(UUID companyId) {
        return walletRepository.findByCompanyIdForUpdate(companyId)
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
