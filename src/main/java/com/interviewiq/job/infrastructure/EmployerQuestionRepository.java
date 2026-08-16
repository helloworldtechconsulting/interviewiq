package com.interviewiq.job.infrastructure;

import com.interviewiq.job.domain.EmployerQuestion;
import com.interviewiq.job.domain.QuestionSafetyStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployerQuestionRepository extends JpaRepository<EmployerQuestion, UUID> {

    List<EmployerQuestion> findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(UUID jobOpeningId);

    /**
     * The approved set for a job, in employer order — this is what fills the core
     * segment of a session's question bank.
     */
    List<EmployerQuestion> findAllByJobOpeningIdAndSafetyStatusOrderByDisplayOrderAscCreatedAtAsc(
            UUID jobOpeningId, QuestionSafetyStatus safetyStatus);

    Optional<EmployerQuestion> findByCompanyIdAndId(UUID companyId, UUID id);

    long countByJobOpeningIdAndSafetyStatus(UUID jobOpeningId, QuestionSafetyStatus safetyStatus);

    /**
     * Claims unscreened questions for the safety filter, using
     * {@code FOR UPDATE SKIP LOCKED} so each pod takes a distinct set (§7.9).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
           SELECT * FROM employer_questions
           WHERE safety_status = 'PENDING'
           ORDER BY created_at ASC
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<EmployerQuestion> claimPendingSafetyChecks(@Param("batchSize") int batchSize);
}
