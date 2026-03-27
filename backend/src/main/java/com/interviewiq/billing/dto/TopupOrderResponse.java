package com.interviewiq.billing.dto;

public record TopupOrderResponse(
        String orderId,
        long amountPaise,
        String currency,
        String keyId
) {
}
