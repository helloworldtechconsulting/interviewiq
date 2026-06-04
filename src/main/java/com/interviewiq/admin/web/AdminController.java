package com.interviewiq.admin.web;

import com.interviewiq.billing.dto.TransactionResponse;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final WalletService walletService;

    public AdminController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /api/v1/admin/companies/{companyId}/wallet/credit
     * Manually credits a company wallet — refunds or promotional credits.
     * Only accessible by SUPER_ADMIN.
     */
    @PostMapping("/companies/{companyId}/wallet/credit")
    public ApiResponse<TransactionResponse> manualCredit(
            @PathVariable UUID companyId,
            @Valid @RequestBody ManualCreditRequest request) {
        return ApiResponse.ok(
                TransactionResponse.from(
                        walletService.manualCredit(companyId, request.amountPaise(), request.reason())
                )
        );
    }

    record ManualCreditRequest(
            @Min(100) long amountPaise,
            @NotBlank String reason
    ) {}
}