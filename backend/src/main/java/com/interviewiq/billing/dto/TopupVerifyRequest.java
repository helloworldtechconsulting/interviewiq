package com.interviewiq.billing.dto;

public record TopupVerifyRequest(
        String razorpayOrderId,
        String razorpayPaymentId,
        String razorpaySignature
) {
}
