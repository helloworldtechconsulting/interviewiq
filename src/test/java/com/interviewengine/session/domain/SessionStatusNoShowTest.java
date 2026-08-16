package com.interviewengine.session.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code NO_SHOW} state's position in the machine (V054, INTIQ-92).
 *
 * <p>The distinction between {@code NO_SHOW} and {@code EXPIRED} is easy to
 * collapse by accident and expensive when it happens, so it is asserted rather
 * than left to the enum being read carefully.
 */
class SessionStatusNoShowTest {

    /**
     * Only a booked session can fail to show up. An {@code INVITED} session never
     * chose a time, so its failure mode is {@code EXPIRED} — reaching
     * {@code NO_SHOW} from there would blame a candidate for missing an
     * appointment they never made.
     */
    @Test
    void noShowIsReachableOnlyFromScheduled() {
        assertThat(SessionStatus.SCHEDULED.canTransitionTo(SessionStatus.NO_SHOW)).isTrue();

        for (SessionStatus from : SessionStatus.values()) {
            if (from != SessionStatus.SCHEDULED) {
                assertThat(from.canTransitionTo(SessionStatus.NO_SHOW))
                        .as("%s should not reach NO_SHOW", from)
                        .isFalse();
            }
        }
    }

    @Test
    void noShowIsTerminal() {
        assertThat(SessionStatus.NO_SHOW.isTerminal()).isTrue();
        assertThat(SessionStatus.NO_SHOW.allowedTransitions()).isEmpty();
    }

    /**
     * A no-show is finished, not pending. If it counted as pending it would sit
     * on the recruiter's dashboard forever as an interview that is about to
     * happen.
     */
    @Test
    void noShowDoesNotCountAsPending() {
        assertThat(SessionStatus.NO_SHOW.isPending()).isFalse();
    }

    /**
     * Capacity is released by the sweep at the moment of transition, so the
     * terminal state itself holds none. Reporting otherwise would make the
     * release look like a double-free to anything reading this flag.
     */
    @Test
    void noShowHoldsNoCapacity() {
        assertThat(SessionStatus.NO_SHOW.holdsCapacity()).isFalse();
        assertThat(SessionStatus.SCHEDULED.holdsCapacity()).isTrue();
    }
}
