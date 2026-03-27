package com.interviewiq.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobOpeningRepository extends JpaRepository<JobOpening, UUID> {
    List<JobOpening> findByCompanyId(UUID companyId);
    List<JobOpening> findByCompanyIdAndStatus(UUID companyId, JobStatus status);
    Optional<JobOpening> findByIdAndCompanyId(UUID id, UUID companyId);
}
