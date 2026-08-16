package com.interviewengine.billing.dto;

import com.interviewengine.company.domain.CompanyStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Platform-staff admin payloads (PRD v2.1, INTIQ-35).
 *
 * <p>These are the only DTOs in the codebase that cross the tenant boundary —
 * everything else is scoped to one company by construction. That is exactly why
 * the endpoints serving them sit behind {@code PLATFORM_STAFF} and are not
 * reachable from a company subdomain.
 */
public final class AdminDtos {

    private AdminDtos() {}

    /**
     * One row of the company list.
     *
     * @param interviewsCompleted   billable interviews to date
     * @param interviewsPending     in flight — invited, scheduled, running or scoring
     * @param balancePaise          paid balance
     * @param promoBalancePaise     outstanding promotional credit, which is platform exposure
     * @param reservedPaise         held against in-flight interviews
     * @param lifetimeSpendPaise    what this company has actually paid us
     */
    public record AdminCompanyRow(
            UUID           companyId,
            String         name,
            String         slug,
            CompanyStatus  status,
            OffsetDateTime createdAt,
            long           interviewsCompleted,
            long           interviewsPending,
            long           balancePaise,
            long           promoBalancePaise,
            long           reservedPaise,
            long           lifetimeSpendPaise
    ) {}

    /**
     * Platform-wide totals.
     *
     * <p>{@code outstandingPromoPaise} is the number the PRD asks to be
     * monitored: promotional credit granted and not yet spent or expired is a
     * liability, and the signup grant is capped against it.
     */
    public record PlatformStats(
            long activeCompanies,
            long interviewsCompleted,
            long interviewsPending,
            long grossRevenuePaise,
            long outstandingPromoPaise,
            long outstandingReservationsPaise
    ) {}

    /**
     * Manual wallet credit by a staff member.
     *
     * <p>The reason is mandatory and validated, not merely encouraged. A manual
     * credit is someone inside the company creating money in a customer's
     * account; an unexplained one is indistinguishable from an error or from
     * fraud six months later, when the person who did it has forgotten and the
     * customer is asking why their balance changed.
     *
     * @param companyId the recipient
     * @param amountPaise how much, in paise
     * @param reason why — recorded on the transaction and surfaced in the audit log
     */
    public record ManualCreditRequest(
            @NotNull(message = "companyId is required.")
            UUID companyId,

            @Min(value = 1, message = "Amount must be at least 1 paisa.")
            long amountPaise,

            @NotBlank(message = "A reason is required for every manual credit.")
            @Size(min = 10, max = 500,
                  message = "The reason must be between 10 and 500 characters — enough to be useful later.")
            String reason
    ) {}
}
