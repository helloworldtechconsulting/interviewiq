package com.interviewengine.auth.service;

import com.interviewengine.auth.infrastructure.OtpRecordRepository;
import com.interviewengine.auth.infrastructure.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Nightly cleanup of expired and used authentication records.
 *
 * <p>Both jobs run in the early hours (2 AM / 2:30 AM UTC) to minimise
 * interference with peak-hour traffic. Virtual-thread executor handles
 * the async invocation from the scheduler.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class AuthCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanupScheduler.class);

    private final OtpRecordRepository    otpRecordRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthCleanupScheduler(OtpRecordRepository otpRecordRepository,
                                RefreshTokenRepository refreshTokenRepository) {
        this.otpRecordRepository    = otpRecordRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Deletes expired and used OTP records. Runs daily at 02:00 UTC. */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void cleanupOtpRecords() {
        int deleted = otpRecordRepository.deleteExpiredAndUsed(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("OTP cleanup: deleted {} records", deleted);
    }

    /** Deletes expired and revoked refresh tokens. Runs daily at 02:30 UTC. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    @Transactional
    public void cleanupRefreshTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("RefreshToken cleanup: deleted {} records", deleted);
    }
}
