package com.interviewiq.billing.web;

import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.dto.TopUpRequest;
import com.interviewiq.billing.dto.TopUpResponse;
import com.interviewiq.billing.dto.TransactionResponse;
import com.interviewiq.billing.dto.VerifyTopUpRequest;
import com.interviewiq.billing.dto.WalletResponse;
import com.interviewiq.billing.service.InvoiceService;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Billing endpoints for the authenticated company.
 *
 * <p>All endpoints require a valid employer JWT. Company context is
 * resolved from the token — no company ID in the URL.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/billing/wallet}      — current balance</li>
 *   <li>{@code GET  /api/v1/billing/transactions} — paginated ledger</li>
 *   <li>{@code POST /api/v1/billing/topup}        — initiate Razorpay top-up</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final WalletService walletService;
    private final InvoiceService invoiceService;

    public BillingController(WalletService walletService, InvoiceService invoiceService) {
        this.walletService  = walletService;
        this.invoiceService = invoiceService;
    }

    /**
     * GET /api/v1/billing/wallet
     * Returns the company's wallet balance (total, reserved, available).
     */
    @GetMapping("/wallet")
    public ApiResponse<WalletResponse> getBalance() {
        return ApiResponse.ok(walletService.getBalance());
    }

    /**
     * GET /api/v1/billing/transactions?page=0&size=20
     * Returns paginated transaction history, newest first.
     */
    @GetMapping("/transactions")
    public ApiResponse<Page<TransactionResponse>> getTransactions(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<WalletTransaction> page = walletService.getTransactions(pageable);
        return ApiResponse.ok(page.map(TransactionResponse::from));
    }

    /**
     * POST /api/v1/billing/topup
     * Creates a Razorpay order for the requested amount.
     * Returns the order ID and key for the frontend checkout widget.
     * Wallet is credited automatically via the Razorpay webhook after payment capture.
     */
    @PostMapping("/topup")
    public ApiResponse<TopUpResponse> initiateTopUp(
            @Valid @RequestBody TopUpRequest request) {
        return ApiResponse.ok(walletService.initiateTopUp(request.amountPaise()));
    }

    /**
     * POST /api/v1/billing/topup/verify
     *
     * <p>Called by the browser as soon as Razorpay's checkout widget reports
     * success, so the balance updates while the customer is still looking at it
     * rather than whenever the webhook happens to arrive.
     *
     * <p>Races the webhook rather than replacing it — whichever lands first
     * credits, the other is a no-op. The signature is verified server-side; the
     * payload is browser-supplied and is not trusted.
     *
     * <p>Returns the updated wallet so the client does not need a second round
     * trip to show the new balance.
     */
    @PostMapping("/topup/verify")
    public ApiResponse<WalletResponse> verifyTopUp(
            @Valid @RequestBody VerifyTopUpRequest request) {
        return ApiResponse.ok(walletService.verifyAndCreditTopUp(
                request.razorpayOrderId(), request.razorpayPaymentId(), request.razorpaySignature()));
    }

    /**
     * GET /api/v1/billing/transactions/{txnId}/invoice
     *
     * <p>The GST tax invoice for a top-up, as a downloadable file. Refused for
     * settlements and promotional credit — neither is a taxable supply, and
     * issuing an invoice for either would double-count the tax.
     */
    @GetMapping(value = "/transactions/{txnId}/invoice", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getInvoice(@PathVariable UUID txnId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoiceService.filename(txnId) + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(invoiceService.render(txnId));
    }
}
