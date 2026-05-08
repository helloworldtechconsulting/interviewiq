package com.interviewiq.auth.service;

import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.shared.exception.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed login attempts per client IP address and enforces a lockout
 * when the failure threshold is exceeded.
 *
 * <h2>Algorithm</h2>
 * <p>Each IP has an {@link AttemptBucket} storing:
 * <ul>
 *   <li>{@code count}       — failures since {@code windowStart}</li>
 *   <li>{@code windowStart} — start of the current counting window</li>
 *   <li>{@code lockedUntil} — non-null while the IP is locked out</li>
 * </ul>
 *
 * <p>On each failure:
 * <ol>
 *   <li>If the last window has expired, reset count to 1 (new window).</li>
 *   <li>Otherwise increment count.</li>
 *   <li>If count ≥ maxFailures, set {@code lockedUntil = now + lockoutDuration}.</li>
 * </ol>
 *
 * <p>On each request before credential check ({@link #checkLockout}):
 * <ol>
 *   <li>If no bucket → allow.</li>
 *   <li>If bucket exists and {@code lockedUntil} is in the future → throw
 *       {@link RateLimitException} with remaining seconds.</li>
 * </ol>
 *
 * <p>On successful login ({@link #resetFailures}): remove the bucket entirely.
 *
 * <h2>Thread safety</h2>
 * <p>{@link ConcurrentHashMap} plus {@code compute()} atomics ensure correctness
 * without explicit locking. Memory grows at most O(unique IPs) — a periodic
 * eviction can be added if memory pressure becomes a concern in production.
 *
 * <h2>Deployment note</h2>
 * <p>State is in-process. For multi-instance deployments, replace the map with
 * a Redis INCR/EXPIRE-backed implementation. For a single-instance ECS service
 * (current architecture) this is sufficient and avoids a Redis dependency.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /**
     * Immutable snapshot of attempt state for a single IP.
     * Using a record (Java 16+) for structural equality and concise syntax.
     */
    private record AttemptBucket(int count, Instant windowStart, Instant lockedUntil) {}

    /** IP → current attempt state. */
    private final ConcurrentHashMap<String, AttemptBucket> buckets = new ConcurrentHashMap<>();

    private final int     maxFailures;
    private final long    windowSeconds;
    private final long    lockoutSeconds;

    public LoginAttemptService(SecurityProperties props) {
        SecurityProperties.LoginAttempt cfg = props.getLoginAttempt();
        this.maxFailures    = cfg.getMaxFailures();
        this.windowSeconds  = cfg.getWindowDuration().toSeconds();
        this.lockoutSeconds = cfg.getLockoutDuration().toSeconds();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Checks whether the given IP is currently locked out.
     *
     * <p>Call this <em>before</em> validating credentials so that locked-out
     * IPs never hit the database at all.
     *
     * @param ip client IP address
     * @throws RateLimitException (HTTP 429 + {@code Retry-After}) if locked out
     */
    public void checkLockout(String ip) {
        AttemptBucket bucket = buckets.get(ip);
        if (bucket == null) return;

        Instant now = Instant.now();
        if (bucket.lockedUntil() != null && now.isBefore(bucket.lockedUntil())) {
            long secondsRemaining = bucket.lockedUntil().getEpochSecond() - now.getEpochSecond();
            log.warn("Login blocked — IP locked out: ip={} secondsRemaining={}", ip, secondsRemaining);
            throw new RateLimitException(secondsRemaining);
        }
    }

    /**
     * Records a failed login attempt for the given IP.
     *
     * <p>Call this <em>after</em> a credential check fails (wrong password,
     * user not found, account disabled, etc.).
     *
     * <p>If the count reaches {@link #maxFailures} within the window,
     * the IP is locked for {@link #lockoutSeconds} seconds.
     *
     * @param ip client IP address
     */
    public void recordFailure(String ip) {
        Instant now = Instant.now();
        buckets.compute(ip, (key, existing) -> {
            if (existing == null) {
                // First failure — start a fresh window
                return new AttemptBucket(1, now, null);
            }

            // Check if the current window has expired
            boolean windowExpired = existing.windowStart()
                    .plusSeconds(windowSeconds)
                    .isBefore(now);

            if (windowExpired) {
                // Expired window — reset to a single failure in a new window
                return new AttemptBucket(1, now, null);
            }

            int newCount = existing.count() + 1;
            Instant lockedUntil = existing.lockedUntil();

            if (newCount >= maxFailures && lockedUntil == null) {
                lockedUntil = now.plusSeconds(lockoutSeconds);
                log.warn("Login lockout triggered: ip={} failures={} lockedUntilEpoch={}",
                        ip, newCount, lockedUntil.getEpochSecond());
            }

            return new AttemptBucket(newCount, existing.windowStart(), lockedUntil);
        });
    }

    /**
     * Clears the failure record for a given IP after a successful login.
     *
     * <p>Must be called on every successful authentication so that a user whose
     * IP was previously accumulating failures gets a clean slate.
     *
     * @param ip client IP address
     */
    public void resetFailures(String ip) {
        buckets.remove(ip);
    }
}
