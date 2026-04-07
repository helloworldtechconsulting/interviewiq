package com.interviewiq.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TopUpRequest(

        /**
         * Amount to top up in paise (1 INR = 100 paise).
         * Minimum: ₹50 = 5000 paise.
         */
        @NotNull(message = "Amount is required.")
        @Min(value = 5000, message = "Minimum top-up is ₹50 (5000 paise).")
        Long amountPaise
) {}
