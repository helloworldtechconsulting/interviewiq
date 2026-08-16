package com.interviewiq.ai.infrastructure;

import com.interviewiq.ai.domain.QuestionTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionTelemetryRepository extends JpaRepository<QuestionTelemetry, UUID> {

    Optional<QuestionTelemetry> findByJobOpeningIdAndBankQuestionId(UUID jobOpeningId, String bankQuestionId);

    /** Every live question for an opening — the console's per-bank view. */
    List<QuestionTelemetry> findAllByJobOpeningIdOrderByBankQuestionIdAsc(UUID jobOpeningId);

    /**
     * Live questions with enough data to judge.
     *
     * <p>The sample gate is applied in the query rather than by filtering in
     * Java, so the sweep does not load every question ever asked in order to
     * discard most of them. Retired rows are excluded here as well: they are
     * never re-evaluated, and they accumulate without bound while the live set
     * stays roughly constant.
     */
    @Query("""
           SELECT t FROM QuestionTelemetry t
           WHERE t.retiredAt IS NULL
             AND t.timesAsked >= :minimumSample
           """)
    List<QuestionTelemetry> findLiveWithMinimumSample(@Param("minimumSample") int minimumSample);

    /** Ids of the questions retired for an opening, so assembly can skip them. */
    @Query("""
           SELECT t.bankQuestionId FROM QuestionTelemetry t
           WHERE t.jobOpeningId = :jobOpeningId
             AND t.retiredAt IS NOT NULL
           """)
    List<String> findRetiredQuestionIds(@Param("jobOpeningId") UUID jobOpeningId);

    /** Console: everything retired, newest first, for the audit view. */
    @Query("""
           SELECT t FROM QuestionTelemetry t
           WHERE t.retiredAt IS NOT NULL
           ORDER BY t.retiredAt DESC
           """)
    List<QuestionTelemetry> findAllRetired();
}
