package com.interviewiq.candidate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID> {
    List<Candidate> findByJobOpeningId(UUID jobOpeningId);
    List<Candidate> findByCompanyId(UUID companyId);
    Optional<Candidate> findByIdAndCompanyId(UUID id, UUID companyId);
    Optional<Candidate> findByJobOpeningIdAndEmail(UUID jobOpeningId, String email);
}
