package com.interviewengine.billing.web;

import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.dto.GrantPromoCreditRequest;
import com.interviewengine.billing.dto.TransactionResponse;
import com.interviewengine.billing.infrastructure.WalletRepository;
import com.interviewengine.billing.service.WalletService;
import com.interviewengine.shared.dto.ApiResponse;
import com.interviewengine.shared.security.SecurityContext;
import jakarta.validation.Valid;
import com.interviewengine.ai.domain.QuestionTelemetry;
import com.interviewengine.ai.infrastructure.QuestionTelemetryRepository;
import com.interviewengine.ai.service.QuestionRetirementService;
import com.interviewengine.billing.dto.AdminDtos.AdminCompanyRow;
import com.interviewengine.billing.dto.AdminDtos.ManualCreditRequest;
import com.interviewengine.billing.dto.AdminDtos.PlatformStats;
import com.interviewengine.billing.service.AdminConsoleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final AdminConsoleService adminConsoleService;
    private final QuestionTelemetryRepository telemetryRepository;
    private final QuestionRetirementService retirementService;

    public InternalConsoleController(WalletService walletService,
                                     WalletRepository walletRepository,
                                     AdminConsoleService adminConsoleService,
                                     QuestionTelemetryRepository telemetryRepository,
                                     QuestionRetirementService retirementService) {
        this.walletService       = walletService;
        this.walletRepository    = walletRepository;
        this.adminConsoleService = adminConsoleService;
        this.telemetryRepository = telemetryRepository;
        this.retirementService   = retirementService;
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

    // =========================================================================
    // Admin panel (INTIQ-35)
    // =========================================================================

    /**
     * GET /api/v1/internal/companies
     *
     * <p>Every company with its interview counts, balances and lifetime spend.
     * The one listing in the product that deliberately crosses the tenant
     * boundary, which is why the whole controller is {@code PLATFORM_STAFF} and
     * not reachable from a company subdomain.
     */
    @GetMapping("/companies")
    public ApiResponse<Page<AdminCompanyRow>> listCompanies(
            @PageableDefault(size = 25) Pageable pageable) {
        return ApiResponse.ok(adminConsoleService.listCompanies(pageable));
    }

    /** GET /api/v1/internal/stats — platform totals for the console header. */
    @GetMapping("/stats")
    public ApiResponse<PlatformStats> platformStats() {
        return ApiResponse.ok(adminConsoleService.platformStats());
    }

    /**
     * POST /api/v1/internal/manual-credit
     *
     * <p>Adds paid balance to a company's wallet, with a mandatory reason.
     *
     * <p>Distinct from {@code /promo-credits}, and the distinction is the point.
     * Promotional credit is a grant: it expires, it is spent before paid balance,
     * and it counts as platform exposure. A manual credit is a correction — a
     * refund for a botched interview, a goodwill gesture, a payment that arrived
     * out of band. It behaves as money the customer paid, because that is what it
     * is standing in for.
     *
     * <p>The reason is validated at 10 characters minimum rather than merely
     * required. "fix" satisfies a {@code @NotBlank} and explains nothing six
     * months later, when the person who typed it has forgotten and a customer is
     * asking why their balance changed.
     */
    @PostMapping("/manual-credit")
    public ApiResponse<TransactionResponse> manualCredit(
            @Valid @RequestBody ManualCreditRequest request) {

        WalletTransaction tx = walletService.applyManualCredit(
                request.companyId(),
                request.amountPaise(),
                request.reason(),
                SecurityContext.requireUserId());

        return ApiResponse.ok(TransactionResponse.from(tx));
    }

    /**
     * GET /api/v1/internal/questions/retired
     *
     * <p>Everything auto-retirement has removed, newest first, with the reason
     * and the statistics that triggered it (§A7.1, INTIQ-93). This is the view
     * that lets a human check whether the automatic rules are behaving.
     */
    @GetMapping("/questions/retired")
    public ApiResponse<List<QuestionTelemetry>> retiredQuestions() {
        return ApiResponse.ok(telemetryRepository.findAllRetired());
    }

    /**
     * GET /api/v1/internal/questions/retirement-preview
     *
     * <p>How many questions the <em>current</em> thresholds would retire, without
     * retiring anything (§A7.2). Changing a retirement threshold has a blast
     * radius that is invisible until it has already happened; being able to ask
     * first is the difference between tuning and gambling.
     */
    @GetMapping("/questions/retirement-preview")
    public ApiResponse<Map<String, Long>> retirementPreview() {
        return ApiResponse.ok(Map.of(
                "wouldRetire", retirementService.previewRetirementCount()));
    }

    /**
     * POST /api/v1/internal/questions/{telemetryId}/reinstate
     *
     * <p>Puts back a question the automatic rules took out too eagerly. The
     * counterpart to auto-retirement, and the reason auto-retirement is safe to
     * run unsupervised: a wrong decision is reversible by someone who can see
     * why it was made.
     */
    @PostMapping("/questions/{telemetryId}/reinstate")
    public ApiResponse<Void> reinstateQuestion(@PathVariable UUID telemetryId) {
        telemetryRepository.findById(telemetryId).ifPresent(row -> {
            row.reinstate();
            telemetryRepository.save(row);
        });
        return ApiResponse.ok();
    }
}
