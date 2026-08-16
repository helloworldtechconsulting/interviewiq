package com.interviewiq.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A staff-initiated promotional credit grant (PRD v2.1 §7.8.3).
 *
 * <p>The reason is mandatory at three layers — here, in
 * {@code WalletService.grantPromotionalCredit}, and as a database CHECK. That is
 * not redundancy for its own sake: free credit with no stated justification makes
 * promotional exposure unauditable, and §7.8.3 requires every grant to be
 * explicable and written to the audit log.
 */
public record GrantPromoCreditRequest(

        @NotNull(message = "Company is required.")
        UUID companyId,

        @NotNull(message = "Amount is required.")
        @Min(value = 1, message = "Grant amount must be positive.")
        Long amountPaise,

        @NotBlank(message = "A reason is required for every promotional grant.")
        @Size(max = 500, message = "Reason must be 500 characters or fewer.")
        String reason,

        /** Optional. Null means the grant does not lapse. */
        OffsetDateTime expiresAt
) {}
