package com.interviewengine.session.infrastructure;

import com.interviewengine.session.domain.InterviewSession;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

    Optional<InterviewSession> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<InterviewSession> findAllByCandidateIdOrderByCreatedAtDesc(UUID candidateId, Pageable pageable);

    Page<InterviewSession> findAllByCompanyIdAndJobOpeningIdOrderByCreatedAtDesc(UUID companyId, UUID jobOpeningId, Pageable pageable);

    /** Company-wide listing — used when no jobOpeningId filter is supplied. */
    Page<InterviewSession> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** Company-wide listing filtered by status. */
    Page<InterviewSession> findAllByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, SessionStatus status, Pageable pageable);

    /**
     * Whether this candidate has ever been invited.
     *
     * <p>Used as the edit guard in {@code CandidateService.update} — a session
     * existing at all means an invite email has been sent, whatever state that
     * session has since reached.
     */
    boolean existsByCandidateId(UUID candidateId);

    /** Whether the candidate has a session in a specific state. */
    boolean existsByCandidateIdAndStatus(UUID candidateId, SessionStatus status);

    /** Whether the candidate has a session in any of the given states. */
    boolean existsByCandidateIdAndStatusIn(UUID candidateId, Collection<SessionStatus> statuses);

    // ── Dashboard counters (INTIQ-73) ────────────────────────────────────────

    long countByCompanyIdAndStatus(UUID companyId, SessionStatus status);

    long countByCompanyIdAndStatusIn(UUID companyId, Collection<SessionStatus> statuses);

    /** No-shows over a rolling window — the all-time total is not actionable. */
    long countByCompanyIdAndStatusAndCreatedAtAfter(
            UUID companyId, SessionStatus status, OffsetDateTime since);

    /**
     * Atomically claims scheduled sessions that are due a reminder, stamping the
     * send timestamp in the same statement.
     *
     * <p>Two mechanisms, doing two different jobs. {@code SKIP LOCKED} stops two
     * pods claiming the same row in the same pass. The {@code IS NULL} predicate
     * plus the stamp is what stops a <em>resend</em> — without it, a pod that
     * died after sending and before committing would send again on recovery, and
     * so would every subsequent pass.
     *
     * <p>Getting this wrong is not a silent duplicate-work bug like double
     * evaluation: it puts six copies of "your interview is in one hour" into a
     * candidate's inbox and damages sender reputation, which outlasts the fix.
     *
     * <p>{@code dueBefore} is the cutoff — the sweep passes {@code now + 24h} for
     * the day-before reminder and {@code now + 1h} for the hour-before one.
     * Sessions already past their start time are excluded: a reminder for an
     * interview that should already have begun is worse than no reminder.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE interview_sessions
           SET reminder_24h_sent_at = now(),
               updated_at           = now()
           WHERE id IN (
               SELECT id FROM interview_sessions
               WHERE status = 'SCHEDULED'
                 AND reminder_24h_sent_at IS NULL
                 AND scheduled_start_at <= :dueBefore
                 AND scheduled_start_at >  :now
               ORDER BY scheduled_start_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<InterviewSession> claimDueFor24hReminder(@Param("now") OffsetDateTime now,
                                                  @Param("dueBefore") OffsetDateTime dueBefore,
                                                  @Param("batchSize") int batchSize);

    /** T-1h counterpart of {@link #claimDueFor24hReminder}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE interview_sessions
           SET reminder_1h_sent_at = now(),
               updated_at          = now()
           WHERE id IN (
               SELECT id FROM interview_sessions
               WHERE status = 'SCHEDULED'
                 AND reminder_1h_sent_at IS NULL
                 AND scheduled_start_at <= :dueBefore
                 AND scheduled_start_at >  :now
               ORDER BY scheduled_start_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<InterviewSession> claimDueFor1hReminder(@Param("now") OffsetDateTime now,
                                                 @Param("dueBefore") OffsetDateTime dueBefore,
                                                 @Param("batchSize") int batchSize);

    /**
     * Atomically claims scheduled sessions whose grace window has elapsed
     * without the candidate arriving.
     *
     * <p>Claimed by moving straight to {@code NO_SHOW}, so a second pod's
     * {@code status = 'SCHEDULED'} predicate no longer matches. That matters more
     * here than for reminders: each claimed row releases a ₹100 reservation, and
     * releasing twice credits a company for money it only ever reserved once —
     * the same defect class as the {@code SessionExpiryService} double-release.
     *
     * <p>The session is <em>not</em> settled. A no-show is not charged (§7.4.5):
     * no interview happened, no evaluation ran, and no cost was incurred beyond
     * question generation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE interview_sessions
           SET status     = 'NO_SHOW',
               ended_at   = now(),
               updated_at = now()
           WHERE id IN (
               SELECT id FROM interview_sessions
               WHERE status = 'SCHEDULED'
                 AND scheduled_start_at < :graceCutoff
               ORDER BY scheduled_start_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<InterviewSession> claimNoShows(@Param("graceCutoff") OffsetDateTime graceCutoff,
                                        @Param("batchSize") int batchSize);

    Optional<InterviewSession> findByInviteTokenHash(String inviteTokenHash);

    Page<InterviewSession> findByStatusAndInviteExpiresAtBefore(
            SessionStatus status, OffsetDateTime threshold, Pageable pageable);

    /** Expirable sessions — INVITED and SCHEDULED both lapse (PRD v2.1 §7.4.4). */
    Page<InterviewSession> findByStatusInAndInviteExpiresAtBefore(
            Collection<SessionStatus> statuses, OffsetDateTime threshold, Pageable pageable);

    /** Sessions awaiting AI question generation — for QuestionGenerationWorker. */
    List<InterviewSession> findAllByQuestionGenerationStatus(PipelineStatus status);

    /**
     * Atomically claims a batch of sessions for question generation.
     *
     * <p>Same discipline as {@code EvaluationReportRepository.claimBatch} and for
     * the same reason (PRD v2.1 §7.9): {@code FOR UPDATE SKIP LOCKED} with an
     * explicit {@code LIMIT}, so each pod claims a distinct set of rows rather
     * than all six pods generating questions for the same session and paying the
     * LLM bill six times over.
     *
     * <p>The lock is released when this statement commits, not held across the
     * ~20-second generation call.
     *
     * @param batchSize   maximum rows to claim in one poll
     * @param staleBefore claims older than this are treated as abandoned by a dead pod
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE interview_sessions
           SET question_generation_status = 'IN_PROGRESS',
               questions_claimed_at       = now(),
               updated_at                 = now()
           WHERE id IN (
               SELECT id FROM interview_sessions
               WHERE question_generation_status = 'PENDING'
                  OR (question_generation_status = 'IN_PROGRESS' AND questions_claimed_at < :staleBefore)
               ORDER BY created_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<InterviewSession> claimForQuestionGeneration(@Param("batchSize") int batchSize,
                                                      @Param("staleBefore") OffsetDateTime staleBefore);

    /**
     * Atomically claims expired invites for the expiry job.
     *
     * <p>Expiry releases the ₹100 reservation and frees any held capacity
     * buckets, so two pods expiring the same session would release the
     * reservation twice. Claiming under {@code SKIP LOCKED} is what prevents
     * that — the same class of bug as duplicate wallet settlement (§7.9).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE interview_sessions
           SET status = 'EXPIRED',
               updated_at = now()
           WHERE id IN (
               SELECT id FROM interview_sessions
               WHERE status IN ('INVITED', 'SCHEDULED')
                 AND invite_expires_at < :now
               ORDER BY invite_expires_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<InterviewSession> claimExpiredInvites(@Param("now") OffsetDateTime now,
                                               @Param("batchSize") int batchSize);

    /** Pending question-generation count, for the KEDA scaler and the SLA alarm. */
    @Query(value = """
           SELECT COUNT(*) FROM interview_sessions
           WHERE question_generation_status = 'PENDING'
           """, nativeQuery = true)
    long countPendingQuestionGeneration();

    /**
     * Loads a session under a row lock, for paths that must check the current
     * status and transition it atomically.
     *
     * <p>Expiry, cancellation and settlement all read-modify-write the status.
     * Doing that unlocked is a race: on two pods both read the old status, both
     * write the new one, and both perform the side effect — releasing a wallet
     * reservation twice, or settling a session twice (PRD v2.1 §7.9).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id")
    Optional<InterviewSession> findByIdForUpdate(@Param("id") UUID id);

    // ── Platform-staff aggregates (INTIQ-35) ─────────────────────────────────
    //
    // Grouped counts for a page of companies, so the admin list costs one query
    // per metric rather than one per row. A join against the company list would
    // produce a row per session and aggregate it back down — fine at 20
    // companies, quietly quadratic in interview history.

    @Query("""
           SELECT s.companyId, COUNT(s) FROM InterviewSession s
           WHERE s.companyId IN :companyIds AND s.status = com.interviewengine.session.domain.SessionStatus.COMPLETED
           GROUP BY s.companyId
           """)
    List<Object[]> countCompletedByCompanyIdIn(@Param("companyIds") Collection<UUID> companyIds);

    @Query("""
           SELECT s.companyId, COUNT(s) FROM InterviewSession s
           WHERE s.companyId IN :companyIds AND s.status IN :statuses
           GROUP BY s.companyId
           """)
    List<Object[]> countByCompanyIdInAndStatusIn(@Param("companyIds") Collection<UUID> companyIds,
                                                 @Param("statuses") Collection<SessionStatus> statuses);

    long countByStatus(SessionStatus status);

    long countByStatusIn(Collection<SessionStatus> statuses);
}
