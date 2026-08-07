package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Optional<InterviewSession> findByInviteTokenHash(String inviteTokenHash);

    /**
     * A page of sessions whose invite window has elapsed but that are still in the
     * given status (INVITED) — drives {@code SessionExpiryJob}'s fetch-then-release loop.
     *
     * <p>Replaces the previous bulk JPQL UPDATE: each stale session must have its
     * wallet reservation released individually (a bulk UPDATE stranded the funds),
     * so the job needs the actual rows, not just a modified count.
     */
    Page<InterviewSession> findByStatusAndInviteExpiresAtBefore(
            SessionStatus status, OffsetDateTime threshold, Pageable pageable);

    /** Sessions awaiting AI question generation — for QuestionGenerationWorker. */
    List<InterviewSession> findAllByQuestionGenerationStatus(PipelineStatus status);

    /**
     * Sessions to process for question generation — PENDING (normal) + IN_PROGRESS (crash recovery).
     * Safe because fixedDelay prevents concurrent scheduler runs within a single JVM.
     */
    List<InterviewSession> findAllByQuestionGenerationStatusIn(Collection<PipelineStatus> statuses);
}
