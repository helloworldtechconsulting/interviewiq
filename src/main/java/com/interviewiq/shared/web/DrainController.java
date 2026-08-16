package com.interviewiq.shared.web;

import com.interviewiq.session.room.RoomSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Graceful drain for pods holding live interviews (PRD v2.1 §7.5.2, Arch v4.0 §5.2).
 *
 * <p>The architecture document is blunt about why this exists: evicting a pod
 * that holds live WebSockets "kills those candidates' interviews mid-sentence",
 * and it calls this "the easiest thing to get wrong, worst consequence". §17
 * rates it HIGH severity <em>and</em> HIGH probability — the highest-risk pairing
 * in the register.
 *
 * <p>The mechanism has four parts and they only work together:
 *
 * <ol>
 *   <li>{@code preStop} calls {@code POST /internal/drain}, which makes the pod
 *       <strong>fail its readiness probe</strong> so the Service stops routing new
 *       traffic to it — while keeping existing WebSockets alive.</li>
 *   <li>A {@code terminationGracePeriodSeconds} of 3900 (the 60-minute maximum
 *       interview plus grace) so Kubernetes waits for those interviews to finish.</li>
 *   <li>A PodDisruptionBudget limiting voluntary evictions.</li>
 *   <li>An HPA {@code scaleDown.stabilizationWindowSeconds} of 3900, so the
 *       autoscaler does not thrash pods that still hold sessions.</li>
 * </ol>
 *
 * <p><strong>Liveness must not kill a draining pod.</strong> This endpoint moves
 * readiness only. A liveness probe that also failed here would have Kubernetes
 * kill the pod mid-drain, defeating the entire mechanism — which is why the
 * manifests point liveness and readiness at different endpoints.
 */
@RestController
@RequestMapping("/internal")
public class DrainController {

    private static final Logger log = LoggerFactory.getLogger(DrainController.class);

    private final ApplicationEventPublisher events;
    private final ApplicationAvailability availability;
    private final RoomSessionRegistry registry;

    public DrainController(ApplicationEventPublisher events,
                           ApplicationAvailability availability,
                           RoomSessionRegistry registry) {
        this.events       = events;
        this.availability = availability;
        this.registry     = registry;
    }

    /**
     * Takes this pod out of rotation without disturbing its live interviews.
     *
     * <p>Called from the Deployment's {@code preStop} hook. Returns the live
     * interview count so an operator draining by hand can see what they are
     * waiting on.
     */
    @PostMapping("/drain")
    public Map<String, Object> drain() {
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);

        int live = registry.liveInterviewCount();
        log.warn("Pod draining: refusing new traffic, {} live interview(s) still held", live);

        return Map.of(
                "status", "draining",
                "liveInterviews", live,
                "message", live > 0
                        ? "Existing interviews will continue. Do not force-terminate this pod."
                        : "No live interviews; this pod is safe to terminate.");
    }

    /**
     * Returns a pod to rotation.
     *
     * <p>For an operator who drained a pod to investigate something and wants it
     * serving again without a restart.
     */
    @PostMapping("/undrain")
    public Map<String, Object> undrain() {
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        log.info("Pod returned to rotation");
        return Map.of("status", "accepting");
    }

    /**
     * Whether this pod is safe to terminate.
     *
     * <p>Deliberately separate from the readiness probe. Readiness answers "should
     * traffic come here?"; this answers "would terminating this pod destroy
     * someone's interview?" — and the second question is the one that matters
     * during a rollout.
     */
    @GetMapping("/drain-status")
    public Map<String, Object> drainStatus() {
        return Map.of(
                "readiness", availability.getReadinessState().toString(),
                "liveInterviews", registry.liveInterviewCount(),
                "safeToTerminate", !registry.hasLiveInterviews());
    }
}
