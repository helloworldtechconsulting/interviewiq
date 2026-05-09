package com.interviewiq.session.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SessionService — focuses on the state-machine transitions
 * (PENDING → SCHEDULED → IN_PROGRESS → COMPLETED) and the wallet
 * reserve/settle/release calls.
 *
 * <p>{@code @Disabled} until the SessionService method signatures are confirmed
 * — see TODO list below for the assertions to make once enabled.
 */
@Disabled("TODO: design mocks once SessionService source is read end-to-end")
class SessionServiceTest {

    @Test
    void create_callsWalletReserveFunds_andTransitionsToScheduled() { /* TODO */ }

    @Test
    void cancelBeforeStart_callsWalletReleaseFunds_andTransitionsToCancelled() { /* TODO */ }

    @Test
    void complete_callsWalletSettleFunds_andTransitionsToCompleted() { /* TODO */ }

    @Test
    void invalidTransition_throwsSessionStateException() { /* TODO */ }
}
