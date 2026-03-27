package com.interviewiq.billing;

import com.interviewiq.billing.dto.*;
import com.interviewiq.common.ApiResponse;
import com.interviewiq.auth.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final BillingService billingService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<WalletResponse>> getBalance(
            @AuthenticationPrincipal User user) {
        log.info("Fetching wallet balance for company: {}", user.getCompanyId());
        var balance = billingService.getBalance(user.getCompanyId());
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    @PostMapping("/topup/initiate")
    public ResponseEntity<ApiResponse<TopupOrderResponse>> initiateTopup(
            @AuthenticationPrincipal User user,
            @RequestBody TopupRequest request) {
        log.info("Initiating topup for company: {} with amount: {}", user.getCompanyId(), request.amountPaise());
        var order = billingService.initiateTopup(user.getCompanyId(), request.amountPaise());
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @PostMapping("/topup/verify")
    public ResponseEntity<ApiResponse<WalletResponse>> verifyTopup(
            @AuthenticationPrincipal User user,
            @RequestBody TopupVerifyRequest request) {
        log.info("Verifying topup for company: {} with order: {}", user.getCompanyId(), request.razorpayOrderId());
        var wallet = billingService.verifyAndCreditTopup(
                user.getCompanyId(),
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature());
        return ResponseEntity.ok(ApiResponse.success(wallet));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Page<WalletTransactionResponse>>> getTransactions(
            @AuthenticationPrincipal User user,
            Pageable pageable) {
        log.info("Fetching transactions for company: {}", user.getCompanyId());
        var txns = billingService.getTransactions(user.getCompanyId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(txns));
    }
}
