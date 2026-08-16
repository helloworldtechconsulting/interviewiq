package com.interviewengine.session.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the live interview sockets held by <em>this</em> pod.
 *
 * <h2>This registry is correct only within one pod — by design</h2>
 *
 * <p>PRD v2.1 §7.9 is explicit about the limitation and requires it be documented
 * in the code, which is what this comment is:
 *
 * <blockquote>
 * "The in-memory WebSocket session registry is <strong>correct only within one
 * pod</strong>. Session affinity plus eviction protection make it correct for
 * the socket itself, but any cross-pod broadcast — an admin force-terminating a
 * session, for instance — will silently no-op. This constraint must be documented
 * in the code. Add Redis pub/sub only when cross-pod messaging is genuinely
 * needed."
 * </blockquote>
 *
 * <p>Concretely: {@link #terminate} reaches only candidates connected to this
 * pod. On a six-pod deployment an admin terminating a session has roughly a
 * one-in-six chance of reaching them. That is an accepted Phase 1 trade-off —
 * Redis pub/sub for cross-pod messaging is listed in Phase 2 (§14) — but it is a
 * silent failure, so it is logged as a warning rather than passing quietly.
 *
 * <p>What makes the single-pod assumption safe for the socket itself is the
 * combination §7.5.2 requires: ingress session affinity so a candidate's
 * WebSocket <em>and</em> their REST calls reach the same pod, a long termination
 * grace period, and eviction protection while an interview is live.
 *
 * <h2>Eviction protection</h2>
 *
 * <p>{@link #hasLiveInterviews()} is what {@code /internal/drain} and the
 * readiness probe consult. §17 rates "a pod eviction or deploy kills a live
 * interview" as HIGH severity and HIGH probability, and §7.5.2 calls it the
 * single worst bug this product could ship — a candidate loses their interview
 * mid-sentence, and no retry can give that back.
 */
@Component
public class RoomSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomSessionRegistry.class);

    /** sessionId → the live socket for that interview. */
    private final Map<UUID, RoomConnection> connections = new ConcurrentHashMap<>();

    /** Exposed as a Prometheus gauge — the concurrent-interview metric from §16. */
    private final AtomicInteger liveCount = new AtomicInteger();

    public void register(UUID sessionId, WebSocketSession socket) {
        RoomConnection previous = connections.put(sessionId,
                new RoomConnection(socket, OffsetDateTime.now(ZoneOffset.UTC)));

        if (previous == null) {
            liveCount.incrementAndGet();
        } else {
            // A reconnect for a session this pod already holds. The old socket is
            // dead or dying; closing it prevents two sockets racing to push the
            // same question.
            closeQuietly(previous.socket());
            log.debug("Replaced an existing socket on reconnect: sessionId={}", sessionId);
        }
        log.info("Interview socket registered: sessionId={} livePodInterviews={}",
                sessionId, liveCount.get());
    }

    public void unregister(UUID sessionId) {
        if (connections.remove(sessionId) != null) {
            liveCount.decrementAndGet();
            log.info("Interview socket released: sessionId={} livePodInterviews={}",
                    sessionId, liveCount.get());
        }
    }

    public Optional<WebSocketSession> socketFor(UUID sessionId) {
        return Optional.ofNullable(connections.get(sessionId)).map(RoomConnection::socket);
    }

    /**
     * Whether this pod is holding any live interview.
     *
     * <p>Consulted by the drain endpoint and the readiness probe: a pod that
     * answers true must not be evicted, because evicting it ends those
     * candidates' interviews mid-sentence.
     */
    public boolean hasLiveInterviews() {
        return liveCount.get() > 0;
    }

    /** Live interview count on this pod. Backs the concurrent-interviews gauge. */
    public int liveInterviewCount() {
        return liveCount.get();
    }

    public Collection<UUID> liveSessionIds() {
        return connections.keySet();
    }

    /**
     * Attempts to reach a session with an out-of-band message.
     *
     * @return false when this pod does not hold the session — which on a
     *         multi-pod deployment usually means another pod does, and the
     *         message was simply not delivered
     */
    public boolean isHeldLocally(UUID sessionId) {
        boolean held = connections.containsKey(sessionId);
        if (!held) {
            // Deliberately a warning. This is the documented cross-pod blind spot
            // and a silent no-op would make it invisible in production.
            log.warn("Session {} is not held by this pod; any broadcast to it will not be "
                    + "delivered (single-pod registry — see RoomSessionRegistry)", sessionId);
        }
        return held;
    }

    private void closeQuietly(WebSocketSession socket) {
        try {
            if (socket.isOpen()) {
                socket.close();
            }
        } catch (Exception e) {
            log.debug("Failed to close a superseded socket: {}", e.getMessage());
        }
    }

    /** One live socket and when it was established. */
    public record RoomConnection(WebSocketSession socket, OffsetDateTime connectedAt) {}
}
