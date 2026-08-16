package com.interviewengine.session.domain;

import com.interviewengine.candidate.domain.CandidateImportBatch;
import com.interviewengine.candidate.domain.ImportBatchStatus;
import com.interviewengine.job.domain.DurationTier;
import com.interviewengine.job.domain.EmployerQuestion;
import com.interviewengine.job.domain.QuestionSafetyStatus;
import com.interviewengine.scheduling.domain.CapacityBucket;
import com.interviewengine.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the v2.1 entity mappings against the Flyway-migrated schema.
 *
 * <p>The base class runs with {@code ddl-auto=validate}, so simply loading this
 * context proves every entity's columns, types and lengths line up with the
 * migrations — a column renamed in SQL but not in Java, or vice versa, fails
 * here rather than at first request in staging.
 *
 * <p>The assertions below then cover the behaviour that lives in the entities
 * themselves and is easy to get subtly wrong: tier arithmetic, bucket alignment,
 * and the lifecycle state machine.
 */
class SchemaMappingIT extends AbstractPostgresIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    void everyV21EntityIsMappedToTheMigratedSchema() {
        // ddl-auto=validate has already run by this point; these confirm the
        // entities are actually registered rather than silently absent.
        assertThat(em.getMetamodel().entity(SessionEvent.class)).isNotNull();
        assertThat(em.getMetamodel().entity(SessionAnswer.class)).isNotNull();
        assertThat(em.getMetamodel().entity(ProctoringEvent.class)).isNotNull();
        assertThat(em.getMetamodel().entity(CapacityBucket.class)).isNotNull();
        assertThat(em.getMetamodel().entity(EmployerQuestion.class)).isNotNull();
        assertThat(em.getMetamodel().entity(CandidateImportBatch.class)).isNotNull();
    }

    // =========================================================================
    // Duration tiers (PRD §7.2.1)
    // =========================================================================

    @Test
    void durationTiersMatchTheSpecifiedQuestionCountsAndLengths() {
        assertThat(DurationTier.QUICK.getMinutes()).isEqualTo(20);
        assertThat(DurationTier.QUICK.getQuestionCount()).isEqualTo(8);

        assertThat(DurationTier.STANDARD.getMinutes()).isEqualTo(35);
        assertThat(DurationTier.STANDARD.getQuestionCount()).isEqualTo(15);

        assertThat(DurationTier.IN_DEPTH.getMinutes()).isEqualTo(45);
        assertThat(DurationTier.IN_DEPTH.getQuestionCount()).isEqualTo(20);

        assertThat(DurationTier.COMPREHENSIVE.getMinutes()).isEqualTo(60);
        assertThat(DurationTier.COMPREHENSIVE.getQuestionCount()).isEqualTo(26);

        assertThat(DurationTier.defaultTier()).isEqualTo(DurationTier.STANDARD);
    }

    @Test
    void bucketSpanMatchesTheWorkedExampleInTheSpec() {
        // "a Comprehensive interview occupies twelve buckets, a Quick screen four"
        assertThat(DurationTier.COMPREHENSIVE.getBucketSpan()).isEqualTo(12);
        assertThat(DurationTier.QUICK.getBucketSpan()).isEqualTo(4);
        assertThat(DurationTier.STANDARD.getBucketSpan()).isEqualTo(7);
        assertThat(DurationTier.IN_DEPTH.getBucketSpan()).isEqualTo(9);
    }

    @Test
    void noTierExceedsTheSixtyMinuteCeiling() {
        for (DurationTier tier : DurationTier.values()) {
            assertThat(tier.getMinutes()).isLessThanOrEqualTo(60);
        }
    }

    // =========================================================================
    // Capacity buckets (PRD §7.4.2)
    // =========================================================================

    @Test
    void alignTruncatesDownToTheContainingBucket() {
        OffsetDateTime t = OffsetDateTime.of(2026, 9, 1, 10, 7, 42, 0, ZoneOffset.UTC);

        assertThat(CapacityBucket.align(t))
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 10, 5, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void alignIsIdempotentOnAnAlreadyAlignedInstant() {
        OffsetDateTime aligned = OffsetDateTime.of(2026, 9, 1, 10, 5, 0, 0, ZoneOffset.UTC);

        assertThat(CapacityBucket.align(aligned)).isEqualTo(aligned);
    }

    @Test
    void alignNormalisesToUtcBeforeTruncating() {
        // 15:37 at +05:30 is 10:07 UTC, which belongs to the 10:05 bucket.
        OffsetDateTime ist = OffsetDateTime.of(2026, 9, 1, 15, 37, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

        assertThat(CapacityBucket.align(ist))
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 10, 5, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void bucketRefusesToOccupyBeyondCapacity() {
        CapacityBucket bucket = new CapacityBucket();
        bucket.setCapacity(2);

        bucket.occupy();
        bucket.occupy();

        assertThat(bucket.hasRoom()).isFalse();
        assertThat(bucket.remainingCapacity()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(bucket::occupy)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseFloorsAtZeroSoADoubleReleaseCannotInflateAvailability() {
        CapacityBucket bucket = new CapacityBucket();
        bucket.setCapacity(2);
        bucket.occupy();

        bucket.release();
        bucket.release();   // erroneous second release

        assertThat(bucket.getOccupiedCount()).isZero();
    }

    // =========================================================================
    // Session lifecycle (PRD §7.4.4)
    // =========================================================================

    @Test
    void startNowPathSkipsScheduledEntirely() {
        assertThat(SessionStatus.INVITED.canTransitionTo(SessionStatus.IN_PROGRESS)).isTrue();
        assertThat(SessionStatus.INVITED.canTransitionTo(SessionStatus.SCHEDULED)).isTrue();
    }

    @Test
    void evaluatingSitsBetweenTheInterviewAndTheReport() {
        assertThat(SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.EVALUATING)).isTrue();
        assertThat(SessionStatus.EVALUATING.canTransitionTo(SessionStatus.COMPLETED)).isTrue();

        // A session may not jump straight to COMPLETED without being scored.
        assertThat(SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.COMPLETED)).isFalse();
    }

    @Test
    void terminalStatesAdmitNoFurtherTransition() {
        for (SessionStatus status : SessionStatus.values()) {
            if (status.isTerminal()) {
                assertThat(status.allowedTransitions()).isEmpty();
            }
        }
    }

    @Test
    void onlyScheduledSessionsHoldCapacityBuckets() {
        assertThat(SessionStatus.SCHEDULED.holdsCapacity()).isTrue();
        assertThat(SessionStatus.INVITED.holdsCapacity()).isFalse();
        assertThat(SessionStatus.IN_PROGRESS.holdsCapacity()).isFalse();
    }

    @Test
    void pendingCountMatchesTheDashboardDefinition() {
        // "pending interviews (INVITED + SCHEDULED + IN_PROGRESS + EVALUATING)" — §7.7
        assertThat(SessionStatus.INVITED.isPending()).isTrue();
        assertThat(SessionStatus.SCHEDULED.isPending()).isTrue();
        assertThat(SessionStatus.IN_PROGRESS.isPending()).isTrue();
        assertThat(SessionStatus.EVALUATING.isPending()).isTrue();

        assertThat(SessionStatus.COMPLETED.isPending()).isFalse();
        assertThat(SessionStatus.EXPIRED.isPending()).isFalse();
    }

    // =========================================================================
    // Employer questions (PRD §7.5.8)
    // =========================================================================

    @Test
    void aRejectedQuestionMustNameTheProhibitedCategory() {
        EmployerQuestion q = new EmployerQuestion();
        q.setQuestionText("Are you planning to have children?");

        q.reject("marital and family status");

        assertThat(q.getSafetyStatus()).isEqualTo(QuestionSafetyStatus.REJECTED);
        assertThat(q.getRejectionReason()).isEqualTo("marital and family status");
        assertThat(q.isUsable()).isFalse();
    }

    @Test
    void aRejectionWithoutACategoryIsRefused() {
        EmployerQuestion q = new EmployerQuestion();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> q.reject("  "))
                .isInstanceOf(com.interviewengine.shared.exception.ValidationException.class);
    }

    @Test
    void onlyApprovedQuestionsAreEligibleForAQuestionBank() {
        EmployerQuestion q = new EmployerQuestion();

        assertThat(q.getSafetyStatus()).isEqualTo(QuestionSafetyStatus.PENDING);
        assertThat(q.isUsable()).isFalse();

        q.approve();
        assertThat(q.isUsable()).isTrue();
    }

    // =========================================================================
    // Import batches (PRD §7.3.1)
    // =========================================================================

    @Test
    void previewSummaryReadsAsTheSpecDescribesIt() {
        CandidateImportBatch batch = new CandidateImportBatch();
        batch.setRowCount(52);
        batch.setValidCount(47);
        batch.setDuplicateCount(3);
        batch.setInvalidCount(2);

        assertThat(batch.previewSummary()).isEqualTo("47 valid, 3 duplicates, 2 invalid");
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.VALIDATING);
    }

    @Test
    void wholeBatchReservationCoversEveryValidRow() {
        CandidateImportBatch batch = new CandidateImportBatch();
        batch.setValidCount(47);

        // 47 candidates at Rs.100 each
        assertThat(batch.requiredReservationPaise(10_000L)).isEqualTo(470_000L);
    }
}
