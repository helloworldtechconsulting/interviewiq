package com.interviewiq.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TopUpRequest(

        /**
         * Amount to top up in paise (1 INR = 100 paise).
         *
         * <p>Minimum ₹100 — one interview. Lowered from ₹500 in PRD v2.1 §7.8.1
         * to remove the "commit ₹500 before you've seen it work" barrier to early
         * adoption. The PRD is explicit that this floor should not be reversed
         * even if small top-ups hurt gateway margin: the answer there is to nudge
         * toward larger top-ups with a bonus, because the low floor is the
         * adoption mechanism.
         *
         * <p>Presets offered in the UI: ₹100 / ₹500 / ₹1,000 / ₹2,500 / ₹5,000 /
         * ₹10,000.
         */
        @NotNull(message = "Amount is required.")
        @Min(value = 10_000, message = "Minimum top-up is ₹100.")
        @Max(value = 100_000_000, message = "Maximum top-up is ₹10,00,000.")
        Long amountPaise
) {}
