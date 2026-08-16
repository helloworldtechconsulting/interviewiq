package com.interviewiq.scheduling.service;

import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.email.service.EmailService;
import com.interviewiq.job.domain.DurationTier;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.scheduling.domain.CapacityBucket;
import com.interviewiq.scheduling.dto.SchedulingDtos.AvailableTimesResponse;
import com.interviewiq.scheduling.dto.SchedulingDtos.BookingResponse;
import com.interviewiq.scheduling.dto.SchedulingDtos.ReadinessResponse;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.SchedulingProperties;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.SessionStateException;
import com.interviewiq.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Candidate-driven scheduling (PRD v2.1 §7.4).
 *
 * <p><strong>The candidate books their own interview.</strong> Employer-published
 * availability windows are deleted entirely in v2.1 — no window-management UI, no
 * {@code POST /jobs/{jobId}/slots}, no employer slot administration anywhere. The
 * change removes a whole recruiter-facing module nobody asked for, removes the
 * "none of the offered times work" drop-off cause, and makes the product's own
 * 24×7 claim literally true, which employer windows had contradicted.
 *
 * <p>Two paths are offered on the invite page:
 *
 * <ul>
 *   <li><strong>Start now</strong> — when questions are ready and capacity is
 *       free. There is no artificial wait.</li>
 *   <li><strong>Pick a time</strong> — any future time with capacity, 24×7.</li>
 * </ul>
 *
 * <p>Rescheduling before the start time is permitted and simply releases the old
 * buckets and occupies new ones. The invite token and the ₹100 reservation are
 * unaffected — moving a booking is a calendar change, not a billing event.
 */
@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    /**
     * Granularity of the times offered in the picker.
     *
     * <p>Capacity is tracked in 5-minute buckets, but offering a candidate every
     * 5-minute start across a 30-day horizon would be 8,640 options. Fifteen
     * minutes is a legible picker; it does not change what is bookable, only what
     * is displayed.
     */
    private static final int SLOT_GRANULARITY_MINUTES = 15;

    private final InterviewSessionRepository sessionRepository;
    private final CapacityService capacityService;
    private final SchedulingProperties schedulingProperties;
    private final CandidateRepository candidateRepository;
    private final CompanyRepository companyRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final EmailService emailService;
    private final IcsCalendarWriter icsCalendarWriter;

    @Value("${app.frontend.base-url:https://app.interviewiq.in}")
    private String frontendBaseUrl;

    public SchedulingService(InterviewSessionRepository sessionRepository,
                             CapacityService capacityService,
                             SchedulingProperties schedulingProperties,
                             CandidateRepository candidateRepository,
                             CompanyRepository companyRepository,
                             JobOpeningRepository jobOpeningRepository,
                             EmailService emailService,
                             IcsCalendarWriter icsCalendarWriter) {
        this.sessionRepository    = sessionRepository;
        this.capacityService      = capacityService;
        this.schedulingProperties = schedulingProperties;
        this.candidateRepository  = candidateRepository;
        this.companyRepository    = companyRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.emailService         = emailService;
        this.icsCalendarWriter    = icsCalendarWriter;
    }

    // =========================================================================
    // The readiness gate (§7.4.3)
    // =========================================================================

    /**
     * Whether this candidate can start immediately, and when they could book.
     *
     * <p>This is what replaced the {@code app.session.invite-buffer} key, which
     * v2.1 deletes from configuration, code and documentation. Thirty minutes
     * survives only as an SLA on question generation, alarmed if breached — a
     * monitoring threshold, never a wait the candidate experiences.
     */
    @Transactional(readOnly = true)
    public ReadinessResponse getReadiness(UUID sessionId) {
        InterviewSession session = requireSession(sessionId);
        DurationTier tier = session.getDurationTier();

        boolean questionsReady = session.areQuestionsReady();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean capacityNow = capacityService.hasRoomFor(now, tier);

        return new ReadinessResponse(
                questionsReady,
                capacityNow,
                questionsReady && capacityNow,
                findEarliestBookable(now, tier),
                tier.getMinutes(),
                tier);
    }

    // =========================================================================
    // Available times (§7.4.2)
    // =========================================================================

    /**
     * Times with capacity across the booking horizon.
     *
     * <p>A time is offered if and only if every bucket the interview would span
     * has room. Nothing else shapes the calendar — with employer windows gone,
     * bucket capacity is the only constraint, which is what resolves the open
     * capacity question from v2.0.
     */
    @Transactional(readOnly = true)
    public AvailableTimesResponse getAvailableTimes(UUID sessionId,
                                                    OffsetDateTime from,
                                                    OffsetDateTime until) {
        InterviewSession session = requireSession(sessionId);
        DurationTier tier = session.getDurationTier();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime windowStart = from == null
                ? now.plus(schedulingProperties.getMinimumNotice())
                : maxOf(from, now.plus(schedulingProperties.getMinimumNotice()));
        OffsetDateTime horizonEnd = now.plus(schedulingProperties.getBookingHorizon());
        OffsetDateTime windowEnd = until == null ? horizonEnd : minOf(until, horizonEnd);

        if (!windowStart.isBefore(windowEnd)) {
            return new AvailableTimesResponse(windowStart, windowEnd, tier.getMinutes(), List.of());
        }

        List<OffsetDateTime> available = findAvailableStarts(windowStart, windowEnd, tier);

        return new AvailableTimesResponse(windowStart, windowEnd, tier.getMinutes(), available);
    }

    // =========================================================================
    // Booking (§7.4.1)
    // =========================================================================

    /**
     * Books the interview for a specific time.
     *
     * <p>Capacity is taken atomically by {@link CapacityService#reserve}, under
     * the row-lock discipline §7.9 requires — two candidates hitting the last
     * slot in a bucket is the obvious race, and the loser gets a clear "choose
     * another slot" rather than an over-booked platform.
     *
     * @throws SessionStateException if the session is not in a bookable state
     * @throws ValidationException   if the requested time is outside the horizon
     */
    @Transactional
    public BookingResponse book(UUID sessionId, OffsetDateTime startAt) {
        InterviewSession session = requireSessionForUpdate(sessionId);
        requireBookable(session);

        DurationTier tier = session.getDurationTier();
        OffsetDateTime aligned = CapacityBucket.align(validateRequestedTime(startAt));

        boolean isReschedule = session.getStatus() == SessionStatus.SCHEDULED;
        if (isReschedule) {
            // Releases the old buckets before taking new ones. The invite token
            // and the ₹100 reservation are untouched (§7.4.1).
            capacityService.reschedule(sessionId, session.getCompanyId(), aligned, tier);
        } else {
            capacityService.reserve(sessionId, session.getCompanyId(), aligned, tier);
        }

        session.setScheduledStartAt(aligned);
        session.setStatus(SessionStatus.SCHEDULED);

        // A reschedule moves the interview, so the reminders that were already
        // sent for the old time are no longer true. Clearing the stamps re-arms
        // the sweep against the new start — without this, a candidate who moves
        // from Friday to Monday gets no reminders at all, having already been
        // reminded about a slot that no longer exists.
        if (isReschedule) {
            session.setReminder24hSentAt(null);
            session.setReminder1hSentAt(null);
        }
        sessionRepository.save(session);

        log.info("Interview {}: sessionId={} startAt={} tier={}",
                isReschedule ? "rescheduled" : "booked", sessionId, aligned, tier);

        sendConfirmation(session, tier, aligned, isReschedule);

        return new BookingResponse(aligned, tier.getMinutes(), session.getStatus().name(),
                "/api/v1/candidate/scheduling/" + sessionId + "/calendar.ics");
    }

    /**
     * Emails the booking or reschedule confirmation with the {@code .ics}
     * attached.
     *
     * <p>Failures are absorbed. The slot is booked and the capacity is taken by
     * the time this runs; throwing here would roll back a successful booking
     * because an SMTP server was briefly unreachable, and the candidate can still
     * download the same {@code .ics} from the page they just booked on.
     */
    private void sendConfirmation(InterviewSession session, DurationTier tier,
                                  OffsetDateTime startAt, boolean isReschedule) {
        try {
            Candidate candidate = candidateRepository.findById(session.getCandidateId()).orElse(null);
            if (candidate == null) {
                return;
            }
            String companyName = companyRepository.findById(session.getCompanyId())
                    .map(Company::getName)
                    .orElse("InterviewIQ");
            String jobTitle = jobOpeningRepository.findById(session.getJobOpeningId())
                    .map(JobOpening::getTitle)
                    .orElse("Interview");
            String joinUrl = frontendBaseUrl + "/interview/room/" + session.getId();

            // SEQUENCE 0 for a first booking, 1 for a reschedule. Calendar clients
            // ignore an update whose SEQUENCE has not advanced, so a reschedule
            // that reused 0 would silently leave the old time in the candidate's
            // calendar — the exact failure the stable UID exists to avoid.
            byte[] ics = icsCalendarWriter.write(
                    session.getId(), isReschedule ? 1 : 0, startAt, tier.getMinutes(),
                    jobTitle, companyName, joinUrl).getBytes(StandardCharsets.UTF_8);

            if (isReschedule) {
                emailService.sendRescheduleConfirmationEmail(
                        candidate.getEmail(), candidate.getFullName(), companyName,
                        startAt, ZoneOffset.UTC, joinUrl, ics, session.getCompanyId());
            } else {
                emailService.sendBookingConfirmationEmail(
                        candidate.getEmail(), candidate.getFullName(), companyName,
                        startAt, ZoneOffset.UTC, joinUrl, ics, session.getCompanyId());
            }

        } catch (RuntimeException e) {
            log.error("Booking confirmation email failed: sessionId={}", session.getId(), e);
        }
    }

    /**
     * Releases a booking's capacity without cancelling the session.
     *
     * <p>Used when a session leaves {@code SCHEDULED} for any reason other than
     * the interview actually starting.
     */
    @Transactional
    public void releaseBooking(UUID sessionId) {
        capacityService.releaseForSession(sessionId);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Walks forward from {@code from} looking for the first bookable start.
     *
     * <p>Returns null when the whole horizon is full — which is a real outcome
     * the UI must handle, not an error. §7.4.2 accepts it explicitly: because
     * capacity is enforced, "the failure mode is 'no time available', not a
     * degraded interview".
     */
    private OffsetDateTime findEarliestBookable(OffsetDateTime from, DurationTier tier) {
        OffsetDateTime start = from.plus(schedulingProperties.getMinimumNotice());
        OffsetDateTime end = from.plus(schedulingProperties.getBookingHorizon());

        List<OffsetDateTime> found = findAvailableStarts(start, end, tier, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<OffsetDateTime> findAvailableStarts(OffsetDateTime from,
                                                     OffsetDateTime until,
                                                     DurationTier tier) {
        return findAvailableStarts(from, until, tier, Integer.MAX_VALUE);
    }

    /**
     * Computes bookable start times over a range.
     *
     * <p>Reads the whole bucket range once and evaluates spans in memory rather
     * than querying per candidate slot. An absent bucket row means an empty
     * bucket, so only occupied buckets need to come back from the database —
     * which is what keeps this inside the sub-500 ms p95 the PRD sets for a
     * 30-day horizon (§8).
     */
    private List<OffsetDateTime> findAvailableStarts(OffsetDateTime from,
                                                     OffsetDateTime until,
                                                     DurationTier tier,
                                                     int limit) {
        OffsetDateTime rangeStart = CapacityBucket.align(from);
        // Read far enough ahead that the last candidate slot's full span is covered.
        OffsetDateTime rangeEnd = until.plusMinutes(tier.getMinutes());

        Map<OffsetDateTime, CapacityBucket> occupied = new HashMap<>();
        capacityService.loadRange(rangeStart, rangeEnd)
                .forEach(b -> occupied.put(b.getBucketStart(), b));

        List<OffsetDateTime> available = new ArrayList<>();
        OffsetDateTime slot = alignToSlotGranularity(rangeStart);

        while (slot.isBefore(until) && available.size() < limit) {
            if (spanHasRoom(slot, tier, occupied)) {
                available.add(slot);
            }
            slot = slot.plusMinutes(SLOT_GRANULARITY_MINUTES);
        }
        return available;
    }

    private boolean spanHasRoom(OffsetDateTime start,
                                DurationTier tier,
                                Map<OffsetDateTime, CapacityBucket> occupied) {
        for (int i = 0; i < tier.getBucketSpan(); i++) {
            CapacityBucket bucket = occupied.get(
                    start.plusMinutes((long) i * DurationTier.BUCKET_MINUTES));
            if (bucket != null && !bucket.hasRoom()) {
                return false;
            }
        }
        return true;
    }

    private OffsetDateTime alignToSlotGranularity(OffsetDateTime t) {
        OffsetDateTime aligned = CapacityBucket.align(t);
        int minute = aligned.getMinute();
        int overshoot = minute % SLOT_GRANULARITY_MINUTES;
        return overshoot == 0 ? aligned : aligned.plusMinutes(SLOT_GRANULARITY_MINUTES - overshoot);
    }

    private OffsetDateTime validateRequestedTime(OffsetDateTime startAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime earliest = now.plus(schedulingProperties.getMinimumNotice());
        OffsetDateTime latest = now.plus(schedulingProperties.getBookingHorizon());

        if (startAt.isBefore(earliest)) {
            throw new ValidationException("Please choose a time at least "
                    + schedulingProperties.getMinimumNotice().toMinutes() + " minutes from now.");
        }
        if (startAt.isAfter(latest)) {
            throw new ValidationException("Interviews can be booked up to "
                    + schedulingProperties.getBookingHorizon().toDays() + " days ahead.");
        }
        return startAt;
    }

    private void requireBookable(InterviewSession session) {
        SessionStatus status = session.getStatus();
        if (status != SessionStatus.INVITED && status != SessionStatus.SCHEDULED) {
            throw new SessionStateException(
                    "This interview can no longer be scheduled (status: " + status + ").");
        }
        if (session.getInviteExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new SessionStateException("This invite link has expired.");
        }
    }

    private InterviewSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    private InterviewSession requireSessionForUpdate(UUID sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    private static OffsetDateTime maxOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static OffsetDateTime minOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    /** Exposed for the SLA metric: how long a session has been waiting on questions. */
    public Duration questionGenerationSla() {
        return schedulingProperties.getQuestionGenerationSla();
    }
}
