package com.interviewiq.session.infrastructure;

import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {

    Optional<EvaluationReport> findByCompanyIdAndSessionId(UUID companyId, UUID sessionId);

    Optional<EvaluationReport> findBySessionId(UUID sessionId);

    /** Reports awaiting or retrying AI evaluation — for EvaluationWorker. */
    List<EvaluationReport> findAllByGenerationStatus(PipelineStatus status);

    /**
     * Reports to process — PENDING (normal) + IN_PROGRESS (crash recovery).
     * Safe because fixedDelay prevents concurrent scheduler runs within a single JVM.
     */
    List<EvaluationReport> findAllByGenerationStatusIn(Collection<PipelineStatus> statuses);
}
