package com.interviewiq.session;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("TODO: wallet top-up + session controller path must be confirmed")
class SessionLifecycleIT extends AbstractIntegrationTest {

    @Test
    void create_assertsReserve_cancelReleasesReserve() {
        // Pre-credit wallet via WalletService.confirmTopUp(...).
        // Create session → expect WalletTransaction with type RESERVATION/PENDING.
        // Cancel session before start → expect status RELEASED.
    }
}
