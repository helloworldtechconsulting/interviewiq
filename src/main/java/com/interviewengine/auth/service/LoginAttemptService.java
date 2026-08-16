package com.interviewengine.auth.service;

import com.interviewengine.auth.config.SecurityProperties;
import com.interviewengine.shared.exception.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private record AttemptBucket(int count, Instant windowStart, Instant lockedUntil) {}

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

    public void recordFailure(String ip) {
        Instant now = Instant.now();
        buckets.compute(ip, (key, existing) -> {
            if (existing == null) {
                return new AttemptBucket(1, now, null);
            }

            boolean windowExpired = existing.windowStart()
                    .plusSeconds(windowSeconds)
                    .isBefore(now);

            if (windowExpired) {
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

    public void resetFailures(String ip) {
        buckets.remove(ip);
    }
}
