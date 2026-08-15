package com.interviewiq.session.scheduler;

import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.email.service.EmailService;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.scheduling.service.CapacityService;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.SchedulingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Marks booked interviews the candidate never attended, releases the money they
 * were holding, and tells the recruiter (PRD v2.1 §7.4.5, INTIQ-92).
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Before this, nothing noticed a no-show. The session sat in
 * {@code SCHEDULED} holding a ₹100 reservation and its capacity buckets until
 * {@link SessionExpiryJob} eventually swept it — but that job keys off
 * {@code invite_expires_at}, a completely different clock. A candidate who books
 * for tomorrow morning and does not turn up keeps the reservation until their
 * <em>invite</em> expires, which can be days later. Meanwhile the buckets they
 * occupied are still refusing other candidates a slot that nobody is using.
 *
 * <h2>No-shows are not charged</h2>
 *
 * <p>No interview happened and no evaluation ran, so there is nothing to settle.
 * The reservation is released in full. That is a deliberate product decision
 * rather than a technical one: charging for a no-show would make the recruiter
 * pay for a candidate's behaviour they cannot control, on a product sold per
 * completed interview.
 *
 * <h2>Ordering</h2>
 *
 * <p>The claim commits first, then funds are released, then the email is sent.
 * The claim is what makes the release safe — a second pod cannot re-claim a row
 * already moved to {@code NO_SHOW}, so the release cannot run twice. Releasing
 * twice would credit a company for money it only ever reserved once, which is
 * the same defect that made {@code SessionExpiryService} unsafe before it took
 * a row lock.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class NoShowSweep {

    private static final Logger log = LoggerFactory.getLogger(NoShowSweep.class);

    private final InterviewSessionRepository sessionRepository;
    private final CandidateRepository        candidateRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final UserRepository             userRepository;
    private final WalletService              walletService;
    private final CapacityService            capacityService;
    private final EmailService               emailService;
    private final SchedulingProperties       schedulingProperties;

    public NoShowSweep(InterviewSessionRepository sessionRepository,
                       CandidateRepository candidateRepository,
                       JobOpeningRepository jobOpeningRepository,
                       UserRepository userRepository,
                       WalletService walletService,
                       CapacityService capacityService,
                       EmailService emailService,
                       SchedulingProperties schedulingProperties) {
        this.sessionRepository    = sessionRepository;
        this.candidateRepository  = candidateRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.userRepository       = userRepository;
        this.walletService        = walletService;
        this.capacityService      = capacityService;
        this.emailService         = emailService;
        this.schedulingProperties = schedulingProperties;
    }

    /**
     * Runs every five minutes.
     *
     * <p>Held money is the reason for the cadence. A reservation released five
     * minutes after the grace window is a company that can invite their next
     * candidate immediately; an hourly sweep would mean a hiring drive stalling
     * on funds that are notionally free.
     */
    @Scheduled(fixedDelayString = "PT5M")
    public void sweep() {
        OffsetDateTime graceCutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minus(schedulingProperties.getNoShowGrace());

        List<InterviewSession> claimed =
                claim(graceCutoff, schedulingProperties.getSweepBatchSize());
        if (claimed.isEmpty()) {
            return;
        }

        int released = 0;
        for (InterviewSession session : claimed) {
            if (releaseAndNotify(session)) {
                released++;
            }
        }
        log.info("NoShowSweep: marked {} no-show(s), released {} reservation(s)",
                claimed.size(), released);
    }

    /** Claim commits before any money moves, so a re-claim is impossible. */
    @Transactional
    protected List<InterviewSession> claim(OffsetDateTime graceCutoff, int batchSize) {
        return sessionRepository.claimNoShows(graceCutoff, batchSize);
    }

    /**
     * Releases the reservation and capacity for one no-show, then notifies the
     * recruiter.
     *
     * <p>Each session gets its own transaction so one failure does not abort the
     * batch — the same reasoning as {@link SessionExpiryJob}. A session that
     * fails here is already {@code NO_SHOW}, so it will not be re-claimed; its
     * reservation is surfaced by the stranded-reservation cleanup runner rather
     * than silently lost.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected boolean releaseAndNotify(InterviewSession session) {
        try {
            walletService.releaseFunds(session.getCompanyId(), session.getId());
            capacityService.releaseForSession(session.getId());

            notifyRecruiter(session);
            return true;

        } catch (RuntimeException e) {
            log.error("No-show release failed, reservation may be stranded: sessionId={} companyId={}",
                    session.getId(), session.getCompanyId(), e);
            return false;
        }
    }

    /**
     * Emails the recruiter who owns the opening.
     *
     * <p>Notification failure is absorbed separately from the release: the money
     * being back is what matters, and an unsent email should not make a
     * successful release look like a failure.
     */
    private void notifyRecruiter(InterviewSession session) {
        try {
            JobOpening job = jobOpeningRepository.findById(session.getJobOpeningId()).orElse(null);
            if (job == null || job.getCreatedBy() == null) {
                return;
            }
            String recruiterEmail = userRepository.findById(job.getCreatedBy())
                    .map(u -> u.getEmail())
                    .orElse(null);
            if (recruiterEmail == null) {
                return;
            }

            String candidateName = candidateRepository.findById(session.getCandidateId())
                    .map(Candidate::getFullName)
                    .orElse("A candidate");

            emailService.sendNoShowNoticeEmail(
                    recruiterEmail, candidateName, job.getTitle(),
                    session.getScheduledStartAt(), session.getCompanyId());

        } catch (RuntimeException e) {
            log.warn("No-show notice email failed: sessionId={}", session.getId(), e);
        }
    }
}
