package com.interviewengine.session.service;

import com.interviewengine.billing.service.WalletService;
import com.interviewengine.scheduling.service.CapacityService;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SessionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryService.class);

    private final InterviewSessionRepository sessionRepository;
    private final WalletService walletService;
    private final CapacityService capacityService;

    public SessionExpiryService(InterviewSessionRepository sessionRepository,
                                WalletService walletService,
                                CapacityService capacityService) {
        this.sessionRepository = sessionRepository;
        this.walletService = walletService;
        this.capacityService = capacityService;
    }

    /**
     * Expires one stale invite and releases everything it was holding.
     *
     * <p><strong>The session is loaded under a row lock.</strong> Reading it
     * unlocked, checking the status and then transitioning is a read-modify-write
     * race: on two pods, both read {@code INVITED}, both write {@code EXPIRED},
     * and both call {@code releaseFunds} — releasing the ₹100 reservation twice
     * and crediting the company for money it never reserved. PRD v2.1 §7.9 puts
     * this in the same class as duplicate wallet settlement, and the lock is what
     * makes the check-then-act atomic. The loser of the race sees {@code EXPIRED}
     * when it acquires the lock and returns false.
     *
     * <p>Both {@code INVITED} and {@code SCHEDULED} are expirable. {@code SCHEDULED}
     * is new in v2.1 — a candidate who booked a time and never showed up still has
     * an invite that lapses, and their capacity buckets must be freed (§7.4.4).
     *
     * @return true if this call is the one that expired the session
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireAndRelease(UUID sessionId) {
        InterviewSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            log.warn("SessionExpiryService: session vanished before expiry: sessionId={}", sessionId);
            return false;
        }
        if (session.getStatus() != SessionStatus.INVITED
                && session.getStatus() != SessionStatus.SCHEDULED) {
            log.debug("SessionExpiryService: skipping session in non-expirable state: sessionId={} status={}",
                    sessionId, session.getStatus());
            return false;
        }

        boolean heldCapacity = session.getStatus().holdsCapacity();

        session.setStatus(SessionStatus.EXPIRED);
        sessionRepository.save(session);

        walletService.releaseFunds(session.getCompanyId(), sessionId);

        if (heldCapacity) {
            capacityService.releaseForSession(sessionId);
        }

        log.info("Expired stale invite and released reservation: sessionId={} companyId={} freedCapacity={}",
                sessionId, session.getCompanyId(), heldCapacity);
        return true;
    }
}
