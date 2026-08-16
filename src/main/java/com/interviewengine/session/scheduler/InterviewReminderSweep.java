package com.interviewengine.session.scheduler;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.company.domain.Company;
import com.interviewengine.company.infrastructure.CompanyRepository;
import com.interviewengine.email.service.EmailService;
import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.scheduling.service.IcsCalendarWriter;
import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.shared.config.SchedulingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Sends the T-24h and T-1h interview reminders (PRD v2.1 §7.4, INTIQ-92).
 *
 * <h2>Why this is a sweep and not a scheduled task per booking</h2>
 *
 * <p>A timer scheduled at booking time lives in one pod's heap. It does not
 * survive a rolling deploy, a scale-in, or a crash — and interviews are booked
 * days ahead, so the window in which a pod must stay alive is far longer than a
 * pod's actual lifetime. A sweep over the database recovers by definition: state
 * is in the row, and whichever pod runs next picks it up.
 *
 * <h2>Duplicate protection</h2>
 *
 * <p>Two layers, because they fail differently:
 *
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} — stops two pods claiming the same row in
 *       the same pass.</li>
 *   <li>{@code reminder_*_sent_at IS NULL}, stamped in the claiming statement —
 *       stops a resend across passes, including after a pod dies between sending
 *       and committing.</li>
 * </ul>
 *
 * <p>The story that asked for this was blunt about the stakes, and rightly:
 * without the claim, six pods means every candidate receives six copies of every
 * reminder. That is the multi-instance duplication bug in its most visible form —
 * it reaches the candidate's inbox directly and damages sender reputation, which
 * takes far longer to repair than the code does.
 *
 * <p><strong>The email is sent after the claim commits, not inside it.</strong>
 * That ordering means a crash between commit and send loses a reminder rather
 * than sending a duplicate. Given the choice, a missed reminder is the better
 * failure: the candidate still has the booking confirmation and the calendar
 * entry, whereas a duplicate is visible, unfixable and erodes trust in every
 * subsequent email.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class InterviewReminderSweep {

    private static final Logger log = LoggerFactory.getLogger(InterviewReminderSweep.class);

    private final InterviewSessionRepository sessionRepository;
    private final CandidateRepository        candidateRepository;
    private final CompanyRepository          companyRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final EmailService               emailService;
    private final IcsCalendarWriter          icsWriter;
    private final SchedulingProperties       schedulingProperties;

    @Value("${app.frontend.base-url:https://app.interviewengine.ai}")
    private String frontendBaseUrl;

    public InterviewReminderSweep(InterviewSessionRepository sessionRepository,
                                  CandidateRepository candidateRepository,
                                  CompanyRepository companyRepository,
                                  JobOpeningRepository jobOpeningRepository,
                                  EmailService emailService,
                                  IcsCalendarWriter icsWriter,
                                  SchedulingProperties schedulingProperties) {
        this.sessionRepository    = sessionRepository;
        this.candidateRepository  = candidateRepository;
        this.companyRepository    = companyRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.emailService         = emailService;
        this.icsWriter            = icsWriter;
        this.schedulingProperties = schedulingProperties;
    }

    /**
     * Runs every five minutes.
     *
     * <p>Frequent enough that a T-1h reminder is never more than five minutes
     * late, cheap enough that the cost is a partial-index scan returning nothing
     * for most of the day.
     */
    @Scheduled(fixedDelayString = "PT5M")
    public void sweep() {
        sendBatch(true);
        sendBatch(false);
    }

    private void sendBatch(boolean oneHourOut) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime dueBefore = now.plus(oneHourOut
                ? schedulingProperties.getSecondReminderLead()
                : schedulingProperties.getFirstReminderLead());

        List<InterviewSession> claimed = claim(now, dueBefore, oneHourOut);
        if (claimed.isEmpty()) {
            return;
        }

        int sent = 0;
        for (InterviewSession session : claimed) {
            if (send(session, oneHourOut)) {
                sent++;
            }
        }
        log.info("InterviewReminderSweep: claimed {} {} reminder(s), sent {}",
                claimed.size(), oneHourOut ? "T-1h" : "T-24h", sent);
    }

    /**
     * The claim runs in its own transaction so the stamp is committed before any
     * email is attempted.
     */
    @Transactional
    protected List<InterviewSession> claim(OffsetDateTime now, OffsetDateTime dueBefore, boolean oneHourOut) {
        int batch = schedulingProperties.getSweepBatchSize();
        return oneHourOut
                ? sessionRepository.claimDueFor1hReminder(now, dueBefore, batch)
                : sessionRepository.claimDueFor24hReminder(now, dueBefore, batch);
    }

    /**
     * Sends one reminder. Failures are logged and absorbed — the stamp is already
     * committed, so a failed send is a lost reminder rather than a stuck row that
     * blocks the rest of the batch.
     */
    private boolean send(InterviewSession session, boolean oneHourOut) {
        try {
            Candidate candidate = candidateRepository.findById(session.getCandidateId()).orElse(null);
            if (candidate == null) {
                log.warn("Reminder skipped, candidate vanished: sessionId={}", session.getId());
                return false;
            }

            String companyName = companyRepository.findById(session.getCompanyId())
                    .map(Company::getName)
                    .orElse("InterviewEngine");
            String joinUrl = frontendBaseUrl + "/interview/room/" + session.getId();

            byte[] ics = oneHourOut
                    ? icsWriter.write(
                            session.getId(),
                            // Sequence 1: this is a re-send of an event the candidate
                            // already has, not a new one. A client that has the
                            // booking confirmation's SEQUENCE 0 copy will accept
                            // this as the same event rather than creating a second.
                            1,
                            session.getScheduledStartAt(),
                            session.getDurationTier().getMinutes(),
                            jobTitle(session),
                            companyName,
                            joinUrl).getBytes(StandardCharsets.UTF_8)
                    : null;

            emailService.sendInterviewReminderEmail(
                    candidate.getEmail(), candidate.getFullName(), companyName,
                    session.getScheduledStartAt(), ZoneOffset.UTC,
                    joinUrl, oneHourOut, ics, session.getCompanyId());
            return true;

        } catch (RuntimeException e) {
            log.error("Reminder send failed: sessionId={} oneHourOut={}", session.getId(), oneHourOut, e);
            return false;
        }
    }

    /** Job title for the calendar summary, falling back to something neutral. */
    private String jobTitle(InterviewSession session) {
        return jobOpeningRepository.findById(session.getJobOpeningId())
                .map(JobOpening::getTitle)
                .orElse("Interview");
    }
}
