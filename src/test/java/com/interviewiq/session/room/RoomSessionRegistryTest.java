package com.interviewiq.session.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-pod socket registry, and the eviction protection it feeds.
 *
 * <p>§17 rates "a pod eviction or deploy kills a live interview" HIGH severity and
 * HIGH probability, and §7.5.2 calls it the single worst bug this product could
 * ship. {@code hasLiveInterviews()} is what stands between a rollout and that
 * outcome.
 */
class RoomSessionRegistryTest {

    private RoomSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RoomSessionRegistry();
    }

    private WebSocketSession openSocket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    // =========================================================================
    // Eviction protection
    // =========================================================================

    @Test
    void aPodWithNoInterviewsIsSafeToTerminate() {
        assertThat(registry.hasLiveInterviews()).isFalse();
        assertThat(registry.liveInterviewCount()).isZero();
    }

    @Test
    void aPodHoldingALiveInterviewMustNotBeEvicted() {
        registry.register(UUID.randomUUID(), openSocket());

        assertThat(registry.hasLiveInterviews()).isTrue();
    }

    @Test
    void thePodBecomesSafeAgainOnceEveryInterviewEnds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.register(first, openSocket());
        registry.register(second, openSocket());

        registry.unregister(first);
        assertThat(registry.hasLiveInterviews()).isTrue();   // one still live

        registry.unregister(second);
        assertThat(registry.hasLiveInterviews()).isFalse();
    }

    @Test
    void theLiveCountTracksRegistrationsExactly() {
        for (int i = 0; i < 5; i++) {
            registry.register(UUID.randomUUID(), openSocket());
        }
        assertThat(registry.liveInterviewCount()).isEqualTo(5);
    }

    @Test
    void unregisteringAnUnknownSessionDoesNotCorruptTheCount() {
        registry.register(UUID.randomUUID(), openSocket());

        registry.unregister(UUID.randomUUID());   // never registered

        // A count driven below zero would make a pod look evictable while it
        // still held an interview.
        assertThat(registry.liveInterviewCount()).isEqualTo(1);
    }

    // =========================================================================
    // Reconnect
    // =========================================================================

    @Test
    void reconnectingReplacesTheSocketWithoutDoubleCountingTheInterview() throws Exception {
        UUID sessionId = UUID.randomUUID();
        WebSocketSession original = openSocket();
        WebSocketSession reconnected = openSocket();

        registry.register(sessionId, original);
        registry.register(sessionId, reconnected);

        assertThat(registry.liveInterviewCount()).isEqualTo(1);
        assertThat(registry.socketFor(sessionId)).contains(reconnected);
        // The superseded socket is closed so two sockets cannot race to push the
        // same question.
        verify(original).close();
        verify(reconnected, never()).close();
    }

    // =========================================================================
    // The documented cross-pod limitation
    // =========================================================================

    @Test
    void aSessionHeldByThisPodIsReportedAsLocal() {
        UUID sessionId = UUID.randomUUID();
        registry.register(sessionId, openSocket());

        assertThat(registry.isHeldLocally(sessionId)).isTrue();
    }

    @Test
    void aSessionOnAnotherPodIsReportedAsNotLocal() {
        // §7.9: "any cross-pod broadcast — an admin force-terminating a session,
        // for instance — will silently no-op." The registry surfaces that rather
        // than pretending the message was delivered.
        assertThat(registry.isHeldLocally(UUID.randomUUID())).isFalse();
    }

    @Test
    void socketLookupForAnUnknownSessionIsEmptyRatherThanThrowing() {
        assertThat(registry.socketFor(UUID.randomUUID())).isEmpty();
    }
}
