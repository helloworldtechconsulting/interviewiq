package com.interviewiq.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The three values Razorpay's checkout widget hands back to the browser on a
 * successful payment.
 *
 * <p>Note what is <em>not</em> here: the amount. It is deliberately not accepted
 * from the client and is read back from the order instead. A valid signature
 * proves the payment happened; it says nothing about whether the caller reported
 * the right figure, and a request-supplied amount would be a way to credit ₹5,000
 * for a ₹100 payment.
 *
 * @param razorpayOrderId   the order created by {@code POST /billing/topup}
 * @param razorpayPaymentId Razorpay's id for the captured payment
 * @param razorpaySignature HMAC-SHA256 of {@code order_id|payment_id}, keyed with the account secret
 */
public record VerifyTopUpRequest(

        @NotBlank(message = "razorpayOrderId is required.")
        String razorpayOrderId,

        @NotBlank(message = "razorpayPaymentId is required.")
        String razorpayPaymentId,

        @NotBlank(message = "razorpaySignature is required.")
        String razorpaySignature
) {}
