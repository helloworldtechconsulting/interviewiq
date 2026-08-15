package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {

    Optional<EvaluationReport> findByCompanyIdAndSessionId(UUID companyId, UUID sessionId);

    Optional<EvaluationReport> findBySessionId(UUID sessionId);

    List<EvaluationReport> findAllByGenerationStatus(PipelineStatus status);

    /**
     * Atomically claims a batch of evaluations for this pod.
     *
     * <p>This single statement is the whole of PRD v2.1 §7.9 for this worker. It
     * does four things that must not be separable:
     *
     * <ol>
     *   <li><strong>Claims a distinct set of rows.</strong>
     *       {@code FOR UPDATE SKIP LOCKED} means each pod takes rows no other pod
     *       holds. Without it, six pods process the same rows — six times the LLM
     *       bill and racing writes on the same report row. Unlike singleton
     *       scheduling, this keeps every pod working in parallel.</li>
     *   <li><strong>Bounds the batch.</strong> The explicit {@code LIMIT} is
     *       required by §7.9; the current design's unbounded fetch means one pod
     *       can claim an entire backlog.</li>
     *   <li><strong>Increments the attempt counter under the row lock.</strong>
     *       The PRD calls this out specifically: "The generationAttempts counter
     *       must be incremented under the row lock, or concurrent pods will race
     *       it past maxAttempts and fail perfectly good sessions."</li>
     *   <li><strong>Marks the rows IN_PROGRESS before releasing the lock,</strong>
     *       so they are no longer claimable once the transaction commits.</li>
     * </ol>
     *
     * <p>The lock is held only for the duration of this statement — deliberately
     * not across the LLM call, which takes ~20 seconds. Holding row locks for
     * that long would serialise the workers and exhaust the connection pool.
     *
     * <p><strong>Staleness recovery.</strong> An IN_PROGRESS row is reclaimable
     * once its claim is older than {@code staleBefore}, which recovers work from
     * a pod that died mid-evaluation. This replaces polling IN_PROGRESS rows
     * unconditionally, which on more than one pod meant every pod reprocessing
     * work another pod was actively doing.
     *
     * @param batchSize   maximum rows to claim in one poll
     * @param staleBefore claims older than this are treated as abandoned
     * @return the claimed reports, already marked IN_PROGRESS with attempts incremented
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE evaluation_reports
           SET generation_status   = 'IN_PROGRESS',
               generation_attempts = generation_attempts + 1,
               claimed_at          = now(),
               updated_at          = now()
           WHERE id IN (
               SELECT id FROM evaluation_reports
               WHERE generation_status = 'PENDING'
                  OR (generation_status = 'IN_PROGRESS' AND claimed_at < :staleBefore)
               ORDER BY created_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<EvaluationReport> claimBatch(@Param("batchSize") int batchSize,
                                      @Param("staleBefore") OffsetDateTime staleBefore);

    /**
     * Pending-work count for the KEDA Postgres scaler, which scales the worker
     * deployment on queue depth (Implementation Architecture Decisions §4).
     */
    @Query(value = """
           SELECT COUNT(*) FROM evaluation_reports
           WHERE generation_status = 'PENDING'
           """, nativeQuery = true)
    long countPendingWork();

    /**
     * Oldest unstarted evaluation, for the report-SLA alarm. The hard promise is
     * 30 minutes from session end (§8).
     */
    @Query(value = """
           SELECT MIN(created_at) FROM evaluation_reports
           WHERE generation_status = 'PENDING'
           """, nativeQuery = true)
    OffsetDateTime oldestPendingCreatedAt();
}
