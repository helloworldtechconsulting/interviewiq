package com.interviewengine.scheduling.service;

import com.interviewengine.company.domain.Company;
import com.interviewengine.company.infrastructure.CompanyRepository;
import com.interviewengine.job.domain.DurationTier;
import com.interviewengine.scheduling.domain.CapacityBucket;
import com.interviewengine.scheduling.infrastructure.CapacityBucketRepository;
import com.interviewengine.scheduling.infrastructure.SessionCapacityReservationRepository;
import com.interviewengine.shared.config.SchedulingProperties;
import com.interviewengine.shared.exception.ConflictException;
import com.interviewengine.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capacity reservation against real PostgreSQL (PRD v2.1 §7.4.2, §7.9).
 *
 * <p>These exercise the constraint the PRD identifies as the obvious failure
 * mode: "Bucket reservation must be atomic and race-free — two candidates
 * hitting the last slot in a bucket at the same moment is the obvious failure."
 */
@Import({CapacityService.class, SchedulingProperties.class})
class CapacityServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired private CapacityService capacityService;
    @Autowired private CapacityBucketRepository bucketRepository;
    @Autowired private SessionCapacityReservationRepository reservationRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private SchedulingProperties schedulingProperties;

    @PersistenceContext private EntityManager em;

    private UUID companyId;
    private OffsetDateTime slot;

    @BeforeEach
    void setUp() {
        Company company = new Company();
        company.setName("Acme Hiring");
        company.setSlug("acme-" + UUID.randomUUID().toString().substring(0, 8));
        companyId = companyRepository.saveAndFlush(company).getId();

        slot = CapacityBucket.align(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
    }

    // =========================================================================
    // Bucket span by tier
    // =========================================================================

    @Test
    void aStandardBookingOccupiesSevenConsecutiveBuckets() {
        UUID sessionId = seedSession();

        capacityService.reserve(sessionId, companyId, slot, DurationTier.STANDARD);
        em.flush();

        // 35 minutes / 5 = 7 buckets.
        assertThat(reservationRepository.findAllBySessionId(sessionId)).hasSize(7);
    }

    @Test
    void aComprehensiveBookingOccupiesTwelveBuckets() {
        UUID sessionId = seedSession();

        capacityService.reserve(sessionId, companyId, slot, DurationTier.COMPREHENSIVE);
        em.flush();

        // The worked example from §7.4.2.
        assertThat(reservationRepository.findAllBySessionId(sessionId)).hasSize(12);
    }

    @Test
    void aQuickScreenOccupiesFourBuckets() {
        UUID sessionId = seedSession();

        capacityService.reserve(sessionId, companyId, slot, DurationTier.QUICK);
        em.flush();

        assertThat(reservationRepository.findAllBySessionId(sessionId)).hasSize(4);
    }

    @Test
    void aMisalignedRequestIsTruncatedToItsContainingBucket() {
        UUID sessionId = seedSession();
        OffsetDateTime misaligned = slot.plusMinutes(2).plusSeconds(37);

        capacityService.reserve(sessionId, companyId, misaligned, DurationTier.QUICK);
        em.flush();

        assertThat(bucketRepository.findByBucketStart(slot)).isPresent();
    }

    // =========================================================================
    // The capacity bar
    // =========================================================================

    @Test
    void bookingIsRefusedOnceEveryBucketInTheSpanIsFull() {
        schedulingProperties.setBucketCapacity(1);
        UUID first = seedSession();
        UUID second = seedSession();

        capacityService.reserve(first, companyId, slot, DurationTier.QUICK);
        em.flush();

        assertThatThrownBy(() -> capacityService.reserve(second, companyId, slot, DurationTier.QUICK))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aRefusedBookingLeavesNoPartialOccupancyBehind() {
        schedulingProperties.setBucketCapacity(1);
        UUID first = seedSession();
        UUID second = seedSession();

        // Occupy only the LAST bucket of what the second booking will need, so
        // the second gets several buckets in before it hits the full one.
        capacityService.reserve(first, companyId, slot.plusMinutes(15), DurationTier.QUICK);
        em.flush();

        assertThatThrownBy(() -> capacityService.reserve(second, companyId, slot, DurationTier.QUICK))
                .isInstanceOf(ConflictException.class);
        em.clear();

        // "A booking that took eight of its twelve buckets and then failed would
        // leak capacity that nothing would ever release." The transaction rolls
        // back, so the second session holds nothing.
        assertThat(reservationRepository.findAllBySessionId(second)).isEmpty();
    }

    @Test
    void bookingsShareABucketUpToTheConfiguredBar() {
        schedulingProperties.setBucketCapacity(3);

        for (int i = 0; i < 3; i++) {
            capacityService.reserve(seedSession(), companyId, slot, DurationTier.QUICK);
        }
        em.flush();
        em.clear();

        assertThat(bucketRepository.findByBucketStart(slot))
                .get()
                .extracting(CapacityBucket::getOccupiedCount)
                .isEqualTo(3);
    }

    // =========================================================================
    // Release and reschedule
    // =========================================================================

    @Test
    void releasingABookingFreesEveryBucketItHeld() {
        schedulingProperties.setBucketCapacity(1);
        UUID sessionId = seedSession();

        capacityService.reserve(sessionId, companyId, slot, DurationTier.QUICK);
        em.flush();

        capacityService.releaseForSession(sessionId);
        em.flush();
        em.clear();

        assertThat(reservationRepository.findAllBySessionId(sessionId)).isEmpty();
        assertThat(bucketRepository.findByBucketStart(slot))
                .get()
                .extracting(CapacityBucket::getOccupiedCount)
                .isEqualTo(0);

        // The freed slot is genuinely reusable.
        assertThat(capacityService.hasRoomFor(slot, DurationTier.QUICK)).isTrue();
    }

    @Test
    void releasingASessionThatHoldsNothingIsANoOp() {
        // Expiry, cancellation and rescheduling can all reach release for the
        // same session, so it has to be idempotent.
        capacityService.releaseForSession(UUID.randomUUID());
    }

    @Test
    void reschedulingMovesOccupancyRatherThanDuplicatingIt() {
        schedulingProperties.setBucketCapacity(1);
        UUID sessionId = seedSession();
        OffsetDateTime later = slot.plusHours(3);

        capacityService.reserve(sessionId, companyId, slot, DurationTier.QUICK);
        em.flush();

        capacityService.reschedule(sessionId, companyId, later, DurationTier.QUICK);
        em.flush();
        em.clear();

        assertThat(bucketRepository.findByBucketStart(slot))
                .get().extracting(CapacityBucket::getOccupiedCount).isEqualTo(0);
        assertThat(bucketRepository.findByBucketStart(CapacityBucket.align(later)))
                .get().extracting(CapacityBucket::getOccupiedCount).isEqualTo(1);
        assertThat(reservationRepository.findAllBySessionId(sessionId)).hasSize(4);
    }

    // =========================================================================
    // Availability probing
    // =========================================================================

    @Test
    void anEmptyBucketRangeReadsAsAvailable() {
        // Buckets are created lazily, so "no row" must mean "empty" rather than
        // "unknown" — otherwise a fresh platform would appear fully booked.
        assertThat(capacityService.hasRoomFor(slot, DurationTier.COMPREHENSIVE)).isTrue();
    }

    @Test
    void availabilityIsFalseWhenAnyBucketInTheSpanIsFull() {
        schedulingProperties.setBucketCapacity(1);
        capacityService.reserve(seedSession(), companyId, slot.plusMinutes(30), DurationTier.QUICK);
        em.flush();
        em.clear();

        // A Standard booking at `slot` spans 35 minutes and would cross the
        // occupied bucket at +30.
        assertThat(capacityService.hasRoomFor(slot, DurationTier.STANDARD)).isFalse();
        // A Quick screen at `slot` spans only 20 minutes and clears it.
        assertThat(capacityService.hasRoomFor(slot, DurationTier.QUICK)).isTrue();
    }

    @Test
    void availabilityIsTwentyFourSevenWithNoQuietHours() {
        // 3am on a Sunday. §7.4.2: "a candidate who wants to interview at 11pm on
        // a Sunday should be able to. This is a selling point, not an oversight."
        OffsetDateTime sundayNight = CapacityBucket.align(
                OffsetDateTime.of(2026, 9, 6, 3, 0, 0, 0, ZoneOffset.UTC));

        assertThat(capacityService.hasRoomFor(sundayNight, DurationTier.COMPREHENSIVE)).isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID seedSession() {
        UUID userId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO users (id, company_id, email, full_name, password_hash, role)
                VALUES (:id, :companyId, :email, 'Recruiter', 'x', 'ADMIN')
                """)
                .setParameter("id", userId)
                .setParameter("companyId", companyId)
                .setParameter("email", "u-" + UUID.randomUUID() + "@example.com")
                .executeUpdate();

        UUID jobId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO job_openings (id, company_id, title, status, jd_extraction_status, created_by)
                VALUES (:id, :companyId, 'Backend Engineer', 'ACTIVE', 'PENDING', :userId)
                """)
                .setParameter("id", jobId)
                .setParameter("companyId", companyId)
                .setParameter("userId", userId)
                .executeUpdate();

        UUID candidateId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO candidates (id, company_id, job_opening_id, email, full_name,
                                        resume_extraction_status, candidate_ref)
                VALUES (:id, :companyId, :jobId, :email, 'Candidate', 'PENDING', :ref)
                """)
                .setParameter("id", candidateId)
                .setParameter("companyId", companyId)
                .setParameter("jobId", jobId)
                .setParameter("email", "c-" + UUID.randomUUID() + "@example.com")
                .setParameter("ref", "cand_" + UUID.randomUUID().toString().replace("-", ""))
                .executeUpdate();

        UUID sessionId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO interview_sessions
                    (id, company_id, job_opening_id, candidate_id, invite_token_hash,
                     invite_expires_at, status, question_generation_status, duration_tier)
                VALUES (:id, :companyId, :jobId, :candidateId, :hash,
                        now() + interval '72 hours', 'INVITED', 'PENDING', 'STANDARD')
                """)
                .setParameter("id", sessionId)
                .setParameter("companyId", companyId)
                .setParameter("jobId", jobId)
                .setParameter("candidateId", candidateId)
                .setParameter("hash", "hash-" + UUID.randomUUID())
                .executeUpdate();

        em.flush();
        return sessionId;
    }
}
