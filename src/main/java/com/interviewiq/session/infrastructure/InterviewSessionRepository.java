package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    Optional<InterviewSession> findByInviteTokenHash(String inviteTokenHash);

    /** Sessions where the invite window has elapsed but status is still INVITED — for SessionExpiryJob. */
    List<InterviewSession> findAllByStatusAndInviteExpiresAtBefore(SessionStatus status, OffsetDateTime threshold);

    /** Sessions awaiting AI question generation — for QuestionGenerationWorker. */
    List<InterviewSession> findAllByQuestionGenerationStatus(PipelineStatus status);

    /**
     * Sessions to process for question generation — PENDING (normal) + IN_PROGRESS (crash recovery).
     * Safe because fixedDelay prevents concurrent scheduler runs within a single JVM.
     */
    List<InterviewSession> findAllByQuestionGenerationStatusIn(Collection<PipelineStatus> statuses);

    /** Bulk-expire INVITED sessions whose invite window has passed. */
    @Modifying
    @Query("UPDATE InterviewSession s SET s.status = :expiredStatus, s.updatedAt = :now " +
           "WHERE s.status = :invitedStatus AND s.inviteExpiresAt < :threshold")
    int expireStaleInvites(@Param("invitedStatus")  SessionStatus invitedStatus,
                           @Param("expiredStatus")  SessionStatus expiredStatus,
                           @Param("threshold")      OffsetDateTime threshold,
                           @Param("now")            OffsetDateTime now);
}
