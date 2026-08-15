package com.interviewiq.session.infrastructure;

import com.interviewiq.company.domain.Company;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code FOR UPDATE SKIP LOCKED} claim behaviour that PRD v2.1 §7.9
 * requires of every polling worker.
 *
 * <p>The defect being guarded against is specific: "Every scheduled worker in the
 * codebase currently polls with a plain derived query and no locking of any kind.
 * On six pods, all six process the same rows: six times the LLM bill, racing
 * writes on the same report row, generationAttempts races that fail healthy
 * sessions, and duplicate wallet settlement."
 *
 * <p>These run against real PostgreSQL, because the behaviour under test is
 * PostgreSQL's — no mock can demonstrate that two claims return disjoint sets.
 */
class WorkClaimingIT extends AbstractPostgresIntegrationTest {

    @Autowired private EvaluationReportRepository evaluationReportRepository;
    @Autowired private CompanyRepository companyRepository;

    @PersistenceContext private EntityManager em;

    private UUID companyId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Company company = new Company();
        company.setName("Acme Hiring");
        company.setSlug("acme-" + UUID.randomUUID().toString().substring(0, 8));
        companyId = companyRepository.saveAndFlush(company).getId();

        userId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO users (id, company_id, email, full_name, password_hash, role)
                VALUES (:id, :companyId, :email, 'Recruiter', 'x', 'ADMIN')
                """)
                .setParameter("id", userId)
                .setParameter("companyId", companyId)
                .setParameter("email", "recruiter-" + UUID.randomUUID() + "@example.com")
                .executeUpdate();
        em.flush();
    }

    // =========================================================================
    // Batch bounding
    // =========================================================================

    @Test
    void claimRespectsTheBatchLimit() {
        seedPendingReports(25);

        List<EvaluationReport> claimed = evaluationReportRepository.claimBatch(10, staleCutoff());

        // §7.9 requires an explicit LIMIT — an unbounded fetch lets one pod claim
        // an entire backlog.
        assertThat(claimed).hasSize(10);
    }

    @Test
    void claimTakesTheOldestWorkFirst() {
        seedPendingReports(5);

        // Claim 3 of 5, then see which 2 are left.
        List<OffsetDateTime> claimedAges = evaluationReportRepository.claimBatch(3, staleCutoff())
                .stream().map(EvaluationReport::getCreatedAt).sorted().toList();
        em.clear();
        List<OffsetDateTime> remainingAges = evaluationReportRepository.claimBatch(2, staleCutoff())
                .stream().map(EvaluationReport::getCreatedAt).sorted().toList();

        // FIFO: everything claimed first must be older than anything left behind.
        // Note this asserts *which* rows are claimed, not the order they come
        // back in — PostgreSQL's UPDATE ... RETURNING makes no ordering promise
        // about its output, only the subquery's ORDER BY selects the rows.
        assertThat(claimedAges).hasSize(3);
        assertThat(remainingAges).hasSize(2);
        assertThat(claimedAges.get(2)).isBeforeOrEqualTo(remainingAges.get(0));
    }

    // =========================================================================
    // The core guarantee: distinct rows per claimer
    // =========================================================================

    @Test
    void successiveClaimsReturnDisjointSets() {
        seedPendingReports(10);

        List<UUID> first = evaluationReportRepository.claimBatch(4, staleCutoff())
                .stream().map(EvaluationReport::getId).toList();
        List<UUID> second = evaluationReportRepository.claimBatch(4, staleCutoff())
                .stream().map(EvaluationReport::getId).toList();

        assertThat(first).hasSize(4);
        assertThat(second).hasSize(4);
        // This is the whole point: the second claimer must not receive rows the
        // first already took.
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    @Test
    void claimedRowsAreNoLongerClaimable() {
        seedPendingReports(3);

        assertThat(evaluationReportRepository.claimBatch(3, staleCutoff())).hasSize(3);

        // All three are now IN_PROGRESS with a fresh claim, so nothing remains.
        assertThat(evaluationReportRepository.claimBatch(3, staleCutoff())).isEmpty();
    }

    // =========================================================================
    // Attempt counting under the lock
    // =========================================================================

    @Test
    void claimIncrementsTheAttemptCounter() {
        seedPendingReports(1);

        List<EvaluationReport> claimed = evaluationReportRepository.claimBatch(1, staleCutoff());

        // "The generationAttempts counter must be incremented under the row lock,
        // or concurrent pods will race it past maxAttempts and fail perfectly
        // good sessions." — §7.5.5
        assertThat(claimed).singleElement()
                .extracting(EvaluationReport::getGenerationAttempts)
                .isEqualTo(1);
    }

    @Test
    void attemptCounterAdvancesByExactlyOnePerClaim() {
        seedPendingReports(1);

        UUID reportId = evaluationReportRepository.claimBatch(1, staleCutoff()).get(0).getId();
        em.clear();

        // Reclaim the same row by treating every existing claim as abandoned.
        evaluationReportRepository.claimBatch(1, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        em.clear();

        // Re-read rather than trusting the RETURNING projection: this asserts what
        // is actually persisted, which is what a second pod would see.
        assertThat(evaluationReportRepository.findById(reportId))
                .get()
                .extracting(EvaluationReport::getGenerationAttempts)
                .isEqualTo(2);
    }

    // =========================================================================
    // Staleness recovery
    // =========================================================================

    @Test
    void aFreshlyClaimedRowIsNotStolenFromAHealthyPod() {
        seedPendingReports(1);
        evaluationReportRepository.claimBatch(1, staleCutoff());

        // Another pod polling moments later must leave in-flight work alone —
        // otherwise the same evaluation runs twice and we pay the LLM twice.
        assertThat(evaluationReportRepository.claimBatch(1, staleCutoff())).isEmpty();
    }

    @Test
    void anAbandonedClaimIsRecoveredOnceItGoesStale() {
        seedPendingReports(1);
        List<EvaluationReport> first = evaluationReportRepository.claimBatch(1, staleCutoff());

        // Simulates a pod that claimed the row and then died: every claim older
        // than "now + 1 minute" counts as abandoned.
        List<EvaluationReport> recovered =
                evaluationReportRepository.claimBatch(1, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        assertThat(recovered).singleElement()
                .extracting(EvaluationReport::getId)
                .isEqualTo(first.get(0).getId());
    }

    // =========================================================================
    // Queue depth, for the KEDA scaler
    // =========================================================================

    @Test
    void pendingCountReflectsOnlyUnclaimedWork() {
        seedPendingReports(6);
        assertThat(evaluationReportRepository.countPendingWork()).isEqualTo(6);

        evaluationReportRepository.claimBatch(4, staleCutoff());

        // The KEDA Postgres scaler scales the worker deployment on this number,
        // so claimed work must not still read as queued.
        assertThat(evaluationReportRepository.countPendingWork()).isEqualTo(2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OffsetDateTime staleCutoff() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    }

    /**
     * Seeds evaluation reports directly, bypassing the session foreign key.
     *
     * <p>Reports are inserted via native SQL with a session row to satisfy the
     * composite FK — the claim query is native, so it must run against real rows
     * rather than a JPA-only fixture.
     */
    private void seedPendingReports(int count) {
        for (int i = 0; i < count; i++) {
            UUID sessionId = UUID.randomUUID();
            em.createNativeQuery("""
                    INSERT INTO job_openings (id, company_id, title, status, jd_extraction_status, created_by)
                    VALUES (:jobId, :companyId, 'Backend Engineer', 'ACTIVE', 'PENDING', :userId)
                    """)
                    .setParameter("jobId", sessionId)   // reuse the uuid; distinct per row
                    .setParameter("companyId", companyId)
                    .setParameter("userId", userId)
                    .executeUpdate();

            UUID candidateId = UUID.randomUUID();
            em.createNativeQuery("""
                    INSERT INTO candidates (id, company_id, job_opening_id, email, full_name,
                                            resume_extraction_status, candidate_ref)
                    VALUES (:id, :companyId, :jobId, :email, 'Test Candidate', 'PENDING', :ref)
                    """)
                    .setParameter("id", candidateId)
                    .setParameter("companyId", companyId)
                    .setParameter("jobId", sessionId)
                    .setParameter("email", "c" + i + "-" + UUID.randomUUID() + "@example.com")
                    .setParameter("ref", "cand_" + UUID.randomUUID().toString().replace("-", ""))
                    .executeUpdate();

            em.createNativeQuery("""
                    INSERT INTO interview_sessions
                        (id, company_id, job_opening_id, candidate_id, invite_token_hash,
                         invite_expires_at, status, question_generation_status, questions_json,
                         duration_tier)
                    VALUES (:id, :companyId, :jobId, :candidateId, :hash,
                            now() + interval '72 hours', 'EVALUATING', 'DONE',
                            (SELECT jsonb_agg(jsonb_build_object(
                                 'order', g, 'text', 'Question ' || g, 'dimension', 'TECHNICAL'))
                             FROM generate_series(1, 15) g),
                            'STANDARD')
                    """)
                    .setParameter("id", sessionId)
                    .setParameter("companyId", companyId)
                    .setParameter("jobId", sessionId)
                    .setParameter("candidateId", candidateId)
                    .setParameter("hash", "hash-" + UUID.randomUUID())
                    .executeUpdate();

            em.createNativeQuery("""
                    INSERT INTO evaluation_reports
                        (id, company_id, session_id, generation_status, generation_attempts, created_at)
                    VALUES (gen_random_uuid(), :companyId, :sessionId, 'PENDING', 0,
                            now() - (:offset * interval '1 second'))
                    """)
                    .setParameter("companyId", companyId)
                    .setParameter("sessionId", sessionId)
                    .setParameter("offset", count - i)   // older rows first
                    .executeUpdate();
        }
        em.flush();
        em.clear();
    }
}
