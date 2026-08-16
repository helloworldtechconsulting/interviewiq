package com.interviewengine.scheduling.service;

import com.interviewengine.job.domain.DurationTier;
import com.interviewengine.scheduling.domain.CapacityBucket;
import com.interviewengine.scheduling.domain.SessionCapacityReservation;
import com.interviewengine.scheduling.infrastructure.CapacityBucketRepository;
import com.interviewengine.scheduling.infrastructure.SessionCapacityReservationRepository;
import com.interviewengine.shared.config.SchedulingProperties;
import com.interviewengine.shared.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reserves and releases platform capacity for interview bookings (PRD v2.1 §7.4.2).
 *
 * <p>Capacity is the <em>only</em> constraint on when a candidate may interview.
 * Employer-published availability windows are deleted in v2.1, so there is
 * nothing else shaping the calendar — and availability is genuinely 24×7, with no
 * business-hours restriction and no blackout periods.
 *
 * <h2>The race this class exists to prevent</h2>
 *
 * <p>Two candidates hitting the last slot in a bucket at the same moment is the
 * obvious failure, and §7.9 is specific that it must be handled "by the same
 * row-lock discipline as §7.9, not by optimistic retry in the UI". So
 * {@link #reserve} loads every bucket the interview spans under
 * {@code SELECT ... FOR UPDATE}, checks them all, and only then takes capacity —
 * check and act inside one lock, never across two statements.
 *
 * <p>Buckets are always locked in ascending time order. That is deadlock
 * avoidance, not tidiness: a Comprehensive booking locks twelve rows, and two
 * overlapping bookings acquiring shared rows in different orders would deadlock.
 */
@Service
public class CapacityService {

    private static final Logger log = LoggerFactory.getLogger(CapacityService.class);

    private final CapacityBucketRepository bucketRepository;
    private final SessionCapacityReservationRepository reservationRepository;
    private final SchedulingProperties schedulingProperties;

    public CapacityService(CapacityBucketRepository bucketRepository,
                           SessionCapacityReservationRepository reservationRepository,
                           SchedulingProperties schedulingProperties) {
        this.bucketRepository      = bucketRepository;
        this.reservationRepository = reservationRepository;
        this.schedulingProperties  = schedulingProperties;
    }

    /**
     * Takes capacity for a booking, or refuses it.
     *
     * <p>All-or-nothing: an interview occupies every bucket it spans, or none of
     * them. A booking that took eight of its twelve buckets and then failed would
     * leak capacity that nothing would ever release.
     *
     * @param sessionId the session being booked
     * @param companyId the session's tenant
     * @param startAt   the requested start; truncated to its containing bucket
     * @param tier      determines how many consecutive buckets are needed
     * @throws ConflictException if any bucket in the span is full
     */
    @Transactional
    public void reserve(UUID sessionId, UUID companyId, OffsetDateTime startAt, DurationTier tier) {
        OffsetDateTime from  = CapacityBucket.align(startAt);
        OffsetDateTime until = from.plusMinutes((long) tier.getBucketSpan() * DurationTier.BUCKET_MINUTES);

        // Locks existing rows in the span, ascending by time.
        List<CapacityBucket> existing = bucketRepository.lockRange(from, until);
        Map<OffsetDateTime, CapacityBucket> byStart = new HashMap<>();
        for (CapacityBucket b : existing) {
            byStart.put(b.getBucketStart(), b);
        }

        int bar = schedulingProperties.getBucketCapacity();
        List<CapacityBucket> span = new ArrayList<>(tier.getBucketSpan());

        for (int i = 0; i < tier.getBucketSpan(); i++) {
            OffsetDateTime slot = from.plusMinutes((long) i * DurationTier.BUCKET_MINUTES);
            CapacityBucket bucket = byStart.get(slot);

            if (bucket == null) {
                // Created lazily — an absent row is an empty bucket. A concurrent
                // creator loses on the unique constraint and retries into the
                // winner's row, which is why bucket_start is UNIQUE.
                bucket = new CapacityBucket();
                bucket.setBucketStart(slot);
                bucket.setCapacity(bar);
                bucket = bucketRepository.saveAndFlush(bucket);
            }

            if (!bucket.hasRoom()) {
                // Refusing the whole booking is correct: partial occupancy would
                // leak capacity with nothing to release it.
                throw new ConflictException(
                        "That time is no longer available. Please choose another slot.");
            }
            span.add(bucket);
        }

        for (CapacityBucket bucket : span) {
            bucket.occupy();
            reservationRepository.save(
                    SessionCapacityReservation.of(companyId, sessionId, bucket.getId()));
        }
        bucketRepository.saveAll(span);

        log.info("Reserved capacity: sessionId={} from={} buckets={} tier={}",
                sessionId, from, span.size(), tier);
    }

    /**
     * Returns the capacity a session was holding.
     *
     * <p>Driven by the recorded reservations rather than recomputed from the
     * session's start time and tier, so release is exact even if the job's tier
     * changed after the booking was made.
     *
     * <p>Idempotent: releasing a session that holds nothing is a no-op, which
     * matters because expiry, cancellation and rescheduling can all reach here
     * for the same session.
     */
    @Transactional
    public void releaseForSession(UUID sessionId) {
        List<SessionCapacityReservation> held = reservationRepository.findAllBySessionId(sessionId);
        if (held.isEmpty()) {
            return;
        }

        List<UUID> bucketIds = held.stream().map(SessionCapacityReservation::getCapacityBucketId).toList();
        List<CapacityBucket> buckets = bucketRepository.findAllByIdIn(bucketIds);

        for (CapacityBucket bucket : buckets) {
            bucket.release();
        }
        bucketRepository.saveAll(buckets);
        reservationRepository.deleteAllBySessionId(sessionId);

        log.info("Released capacity: sessionId={} buckets={}", sessionId, buckets.size());
    }

    /**
     * Moves a booking to a new time.
     *
     * <p>Releases the old buckets and occupies new ones. The invite token and the
     * ₹100 reservation are untouched — rescheduling is a calendar change, not a
     * billing event (§7.4.1).
     */
    @Transactional
    public void reschedule(UUID sessionId, UUID companyId, OffsetDateTime newStartAt, DurationTier tier) {
        releaseForSession(sessionId);
        reserve(sessionId, companyId, newStartAt, tier);
    }

    /**
     * Buckets in a range that already carry occupancy.
     *
     * <p>Only occupied buckets exist as rows, so the caller can treat anything
     * absent from the result as empty. That is what lets the available-times
     * query evaluate a 30-day horizon from one read.
     */
    @Transactional(readOnly = true)
    public List<CapacityBucket> loadRange(OffsetDateTime from, OffsetDateTime until) {
        return bucketRepository.findRange(from, until);
    }

    /**
     * Whether an interview starting at this instant would fit.
     *
     * <p>Reads without locking — this answers "should we offer this time?", and a
     * time that is free when offered may be taken by the time the candidate
     * commits. {@link #reserve} is the authority, and it re-checks under a lock.
     */
    @Transactional(readOnly = true)
    public boolean hasRoomFor(OffsetDateTime startAt, DurationTier tier) {
        OffsetDateTime from  = CapacityBucket.align(startAt);
        OffsetDateTime until = from.plusMinutes((long) tier.getBucketSpan() * DurationTier.BUCKET_MINUTES);

        int bar = schedulingProperties.getBucketCapacity();
        Map<OffsetDateTime, CapacityBucket> byStart = new HashMap<>();
        for (CapacityBucket b : bucketRepository.findRange(from, until)) {
            byStart.put(b.getBucketStart(), b);
        }

        for (int i = 0; i < tier.getBucketSpan(); i++) {
            CapacityBucket bucket = byStart.get(from.plusMinutes((long) i * DurationTier.BUCKET_MINUTES));
            // An absent bucket is an empty one, as long as the configured bar is
            // above zero.
            if (bucket == null) {
                if (bar <= 0) return false;
                continue;
            }
            if (!bucket.hasRoom()) return false;
        }
        return true;
    }
}
