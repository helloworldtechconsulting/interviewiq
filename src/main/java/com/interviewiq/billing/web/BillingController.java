package com.interviewiq.billing.web;

import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.dto.TopUpRequest;
import com.interviewiq.billing.dto.TopUpResponse;
import com.interviewiq.billing.dto.TransactionResponse;
import com.interviewiq.billing.dto.WalletResponse;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

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

    public BillingController(WalletService walletService) {
        this.walletService = walletService;
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
     * GET /api/v1/billing/transactions/{id}/invoice
     * Returns the Razorpay invoice URL for a TOPUP transaction.
     */
    @GetMapping("/transactions/{id}/invoice")
    public ApiResponse<String> getInvoiceUrl(@PathVariable UUID id) {
        return ApiResponse.ok(walletService.getInvoiceUrl(id));
    }
}
