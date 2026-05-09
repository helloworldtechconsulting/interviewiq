package com.interviewiq.billing;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("TODO: requires authenticated principal + stubbed Razorpay order response")
class BillingIT extends AbstractIntegrationTest {

    @Test
    void walletBalance_topup_insufficientBalanceFlow() {
        // 1. GET /api/v1/billing/wallet → 200, balance==0
        // 2. POST /api/v1/billing/topup → 200, returns rzp_test order id (mocked)
        // 3. Direct walletService.confirmTopUp(...) → balance grows
        // 4. Attempt to create a session that costs > balance → 402/400 InsufficientBalance
    }
}
