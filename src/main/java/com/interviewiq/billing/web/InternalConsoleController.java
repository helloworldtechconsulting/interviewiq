package com.interviewiq.billing.web;

import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.dto.GrantPromoCreditRequest;
import com.interviewiq.billing.dto.TransactionResponse;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.shared.security.SecurityContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The internal staff console (PRD v2.1 §11, §7.8.3).
 *
 * <p><strong>Every endpoint here is restricted to {@code PLATFORM_STAFF}.</strong>
 * §7.1.3 requires that "the grant endpoint is restricted to the internal console
 * role, requires a reason, and writes an AuditLog row. No employer-facing path
 * can create a PROMO_CREDIT transaction." A customer's own ADMIN role explicitly
 * does not qualify — otherwise every customer could mint themselves free
 * interviews.
 *
 * <ul>
 *   <li>{@code POST /api/v1/internal/promo-credits} — grant promotional credit</li>
 *   <li>{@code GET  /api/v1/internal/promo-exposure} — total outstanding free credit</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/internal")
@PreAuthorize("hasAuthority('PLATFORM_STAFF')")
public class InternalConsoleController {

    private final WalletService walletService;
    private final WalletRepository walletRepository;

    public InternalConsoleController(WalletService walletService,
                                     WalletRepository walletRepository) {
        this.walletService    = walletService;
        this.walletRepository = walletRepository;
    }

    /**
     * Grants promotional credit, with a mandatory reason and an optional expiry.
     *
     * <p>The grant is audited by {@code @Auditable} on the service method, and
     * the granting staff user is recorded on the transaction itself so the ledger
     * is self-explaining without a join to the audit log.
     */
    @PostMapping("/promo-credits")
    public ApiResponse<TransactionResponse> grantPromotionalCredit(
            @Valid @RequestBody GrantPromoCreditRequest request) {

        WalletTransaction tx = walletService.grantPromotionalCredit(
                request.companyId(),
                request.amountPaise(),
                request.reason(),
                request.expiresAt(),
                SecurityContext.requireUserId());

        return ApiResponse.ok(TransactionResponse.from(tx));
    }

    /**
     * Total outstanding promotional credit across all companies.
     *
     * <p>"Grants are capped and monitored; the internal dashboard tracks total
     * promotional exposure" (§7.8.3). This is also an alarm metric (§8).
     */
    @GetMapping("/promo-exposure")
    public ApiResponse<Map<String, Long>> promotionalExposure() {
        return ApiResponse.ok(Map.of(
                "totalPromotionalExposurePaise", walletRepository.totalPromotionalExposurePaise()));
    }
}
