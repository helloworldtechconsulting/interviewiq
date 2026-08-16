package com.interviewengine.billing.dto;

/**
 * Returned after initiating a Razorpay top-up order.
 *
 * <p>The frontend uses {@code razorpayOrderId} and {@code keyId} to open the
 * Razorpay checkout modal. After the user completes payment, the frontend
 * posts the payment ID and signature to the Razorpay webhook endpoint — the
 * backend confirms and credits the wallet automatically.
 */
public record TopUpResponse(
        String razorpayOrderId,
        long   amountPaise,
        String currency,
        String keyId
) {}
