package com.interviewiq.session.scheduler;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.session.service.SessionExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled job that expires INVITED sessions whose invite window has passed and
 * releases the wallet reservation each one holds.
 *
 * <p>Runs daily at 03:00 UTC (low-traffic window).
 *
 * <p><b>Why not a bulk UPDATE:</b> a billing reservation is created the moment a
 * session is persisted in INVITED state ({@code SessionService.create}) — not when
 * it moves to IN_PROGRESS. A single {@code UPDATE ... SET status = EXPIRED} would flip
 * the rows without ever calling {@link com.interviewiq.billing.service.WalletService#releaseFunds},
 * permanently stranding each session's cost in {@code reservedPaise}. Instead this job
 * fetches stale sessions a page at a time and expires-and-releases each one in its own
 * transaction via {@link SessionExpiryService#expireAndRelease(java.util.UUID)}.
 *
 * <p>The job method itself is intentionally <em>not</em> {@code @Transactional}: each
 * per-session unit of work owns its transaction, so one failing session is logged and
 * skipped without aborting the whole batch.
 */
@Component
public class SessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryJob.class);

    private static final int PAGE_SIZE = 100;

    private final InterviewSessionRepository sessionRepository;
    private final SessionExpiryService sessionExpiryService;

    public SessionExpiryJob(InterviewSessionRepository sessionRepository,
                            SessionExpiryService sessionExpiryService) {
        this.sessionRepository = sessionRepository;
        this.sessionExpiryService = sessionExpiryService;
    }

    /**
     * Expire all stale INVITED sessions and release their reservations.
     *
     * <p>Scheduled at 03:00 UTC daily. Iterates by always re-fetching the first page:
     * each processed session leaves the INVITED set, so the query surface shrinks until
     * it is empty. Sessions skipped by a concurrent transition (still INVITED but touched
     * elsewhere) simply reappear on the next page and are retried.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void expireStaleInvites() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int expired = 0;
        int failed = 0;

        while (true) {
            // Both INVITED and SCHEDULED lapse. SCHEDULED is new in v2.1: a
            // candidate who booked a time and never showed up still has an invite
            // that expires, and their capacity buckets must be freed (§7.4.4).
            List<InterviewSession> batch = sessionRepository.findByStatusInAndInviteExpiresAtBefore(
                    List.of(SessionStatus.INVITED, SessionStatus.SCHEDULED),
                    now, PageRequest.of(0, PAGE_SIZE)).getContent();

            if (batch.isEmpty()) {
                break;
            }

            int advancedThisRound = 0;
            for (InterviewSession session : batch) {
                try {
                    if (sessionExpiryService.expireAndRelease(session.getId())) {
                        expired++;
                    }
                    advancedThisRound++;
                } catch (Exception e) {
                    failed++;
                    log.error("SessionExpiryJob: failed to expire sessionId={}: {}",
                            session.getId(), e.getMessage(), e);
                }
            }

            if (advancedThisRound == 0) {
                log.error("SessionExpiryJob: {} session(s) could not be expired after a full "
                        + "page of failures; aborting run to avoid an infinite loop", batch.size());
                break;
            }
        }

        if (expired > 0 || failed > 0) {
            log.info("SessionExpiryJob: expired {} stale INVITED session(s), {} failed", expired, failed);
        } else {
            log.debug("SessionExpiryJob: no stale sessions found");
        }
    }
}
