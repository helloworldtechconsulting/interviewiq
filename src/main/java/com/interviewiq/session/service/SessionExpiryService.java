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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireAndRelease(UUID sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("SessionExpiryService: session vanished before expiry: sessionId={}", sessionId);
            return false;
        }
        if (session.getStatus() != SessionStatus.INVITED) {
            log.debug("SessionExpiryService: skipping non-INVITED session: sessionId={} status={}",
                    sessionId, session.getStatus());
            return false;
        }

        session.setStatus(SessionStatus.EXPIRED);
        sessionRepository.save(session);

        walletService.releaseFunds(session.getCompanyId(), sessionId);

        log.info("Expired stale invite and released reservation: sessionId={} companyId={}",
                sessionId, session.getCompanyId());
        return true;
    }
}
