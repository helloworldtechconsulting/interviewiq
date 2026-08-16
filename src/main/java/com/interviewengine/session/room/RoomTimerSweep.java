package com.interviewengine.session.room;

import com.interviewengine.session.service.InterviewRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enforces the per-tier hard timer on live interviews (PRD v2.1 §7.5.2, §7.5.7).
 *
 * <p>Pushes {@code timer.warning} at 10, 5 and 1 minute before the cutoff, and
 * terminates the session when the tier's limit is reached — 20 minutes for a
 * Quick screen through to 60 for Comprehensive.
 *
 * <h2>Why a sweep rather than a timer per interview</h2>
 *
 * <p>A {@code ScheduledFuture} per session would have to be cancelled on every
 * exit path — the candidate ending early, the socket dropping, an admin
 * cancelling, the pod restarting — and a single missed cancellation leaves a task
 * that fires against a finished session. A sweep over the sockets this pod
 * actually holds has no such bookkeeping, and it recovers automatically after a
 * pod restart because the registry is rebuilt by reconnecting clients.
 *
 * <p>It runs on every pod, including web pods, because it drives <em>sockets</em>
 * rather than queued work: only the pod holding a candidate's socket can push a
 * warning to them. That is the same single-pod locality the registry documents,
 * and here it is exactly what is wanted.
 */
@Component
@ConditionalOnProperty(name = "app.room.timer-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class RoomTimerSweep {

    private static final Logger log = LoggerFactory.getLogger(RoomTimerSweep.class);

    private final RoomSessionRegistry registry;
    private final InterviewRoomService roomService;

    public RoomTimerSweep(RoomSessionRegistry registry, InterviewRoomService roomService) {
        this.registry    = registry;
        this.roomService = roomService;
    }

    /**
     * Runs every 30 seconds.
     *
     * <p>Frequent enough that a warning lands within half a minute of its mark and
     * an over-running interview is cut promptly, and cheap enough to be
     * irrelevant: it iterates the sockets on this pod, which at launch volume is
     * roughly a dozen.
     */
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void sweep() {
        for (UUID sessionId : registry.liveSessionIds()) {
            registry.socketFor(sessionId).ifPresent(socket -> {
                try {
                    roomService.checkTimers(sessionId, socket);
                } catch (Exception e) {
                    // One session's failure must not stop the sweep — the next
                    // session in the list may be the one about to overrun.
                    log.error("Timer check failed for sessionId={}", sessionId, e);
                }
            });
        }
    }
}
