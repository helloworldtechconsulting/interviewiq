package com.interviewiq.session.service;

import com.interviewiq.billing.service.WalletService;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional worker that expires a single stale INVITED session and releases its
 * wallet reservation atomically.
 *
 * <p>Deliberately a separate bean from {@link com.interviewiq.session.scheduler.SessionExpiryJob}
 * so that the {@code @Transactional} boundary is honoured by Spring's proxy: the job
 * iterates pages with no surrounding transaction and calls {@link #expireAndRelease(UUID)}
 * once per session. Each call is its own transaction (REQUIRES_NEW) so a failure on one
 * session neither rolls back nor aborts the rest of the batch.
 *
 * <p>Why the reservation must be released here: a reservation is created when the session
 * is first persisted in INVITED state ({@link SessionService#create}), <em>not</em> when it
 * moves to STARTED. Expiring an invite without releasing therefore strands
 * {@code reservedPaise} permanently.
 */
@Service
public class SessionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryService.class);

    private final InterviewSessionRepository sessionRepository;
    private final WalletService walletService;

    public SessionExpiryService(InterviewSessionRepository sessionRepository,
                                WalletService walletService) {
        this.sessionRepository = sessionRepository;
        this.walletService = walletService;
    }

    /**
     * Transitions one session INVITED → EXPIRED and releases its wallet reservation.
     *
     * <p>Runs in its own transaction. Idempotent and race-safe: re-loads the session and
     * only acts if it is still INVITED, so a concurrent start/cancel or a re-run of the
     * job is a no-op. {@link WalletService#releaseFunds} is itself idempotent (no PENDING
     * reservation → warn and return), so a partially-processed session is safe to retry.
     *
     * @param sessionId the stale INVITED session to expire
     * @return {@code true} if this call performed the expiry; {@code false} if it was a no-op
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireAndRelease(UUID sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("SessionExpiryService: session vanished before expiry: sessionId={}", sessionId);
            return false;
        }
        if (session.getStatus() != SessionStatus.INVITED) {
            // Concurrently started/cancelled/already-expired — nothing to do.
            log.debug("SessionExpiryService: skipping non-INVITED session: sessionId={} status={}",
                    sessionId, session.getStatus());
            return false;
        }

        session.setStatus(SessionStatus.EXPIRED);
        sessionRepository.save(session);   // updatedAt stamped by @PreUpdate

        // Return the reservation created at INVITED time. Atomic with the status flip:
        // if the release fails the EXPIRED transition rolls back too.
        walletService.releaseFunds(session.getCompanyId(), sessionId);

        log.info("Expired stale invite and released reservation: sessionId={} companyId={}",
                sessionId, session.getCompanyId());
        return true;
    }
}
