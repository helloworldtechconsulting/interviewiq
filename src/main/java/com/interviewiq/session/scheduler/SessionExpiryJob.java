package com.interviewiq.session.scheduler;

import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Scheduled job that bulk-expires INVITED sessions whose invite window has passed.
 *
 * <p>Runs daily at 03:00 UTC (low-traffic window). Uses a single JPQL bulk UPDATE
 * so the work is done in one DB round-trip rather than N individual saves.
 *
 * <p>The threshold is "now" — any session still in INVITED state with
 * {@code inviteExpiresAt < now} is transitioned to EXPIRED. No wallet release is
 * performed here because a reservation is only created when the session moves to
 * STARTED; INVITED sessions have no outstanding reservation.
 */
@Component
public class SessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryJob.class);

    private final InterviewSessionRepository sessionRepository;

    public SessionExpiryJob(InterviewSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Expire all stale INVITED sessions.
     *
     * <p>Scheduled at 03:00 UTC daily. Uses enum parameters (not string literals)
     * in the JPQL query to avoid Hibernate type-coercion failures.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void expireStaleInvites() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int count = sessionRepository.expireStaleInvites(
                SessionStatus.INVITED,
                SessionStatus.EXPIRED,
                now,          // threshold — sessions with inviteExpiresAt < now
                now           // updatedAt stamp for the bulk update
        );
        if (count > 0) {
            log.info("SessionExpiryJob: expired {} stale INVITED session(s)", count);
        } else {
            log.debug("SessionExpiryJob: no stale sessions found");
        }
    }
}
