package com.interviewiq.candidate.infrastructure;

import com.interviewiq.candidate.domain.CandidateImportBatch;
import com.interviewiq.candidate.domain.ImportBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateImportBatchRepository extends JpaRepository<CandidateImportBatch, UUID> {

    Optional<CandidateImportBatch> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<CandidateImportBatch> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    List<CandidateImportBatch> findAllByJobOpeningIdOrderByCreatedAtDesc(UUID jobOpeningId);

    long countByJobOpeningIdAndStatus(UUID jobOpeningId, ImportBatchStatus status);
}
