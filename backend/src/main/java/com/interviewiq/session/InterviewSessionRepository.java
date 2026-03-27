package com.interviewiq.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    Optional<InterviewSession> findByInviteToken(String inviteToken);
    List<InterviewSession> findByCandidateId(UUID candidateId);
    List<InterviewSession> findByCompanyId(UUID companyId);
    List<InterviewSession> findByJobOpeningId(UUID jobOpeningId);
    Optional<InterviewSession> findByIdAndCompanyId(UUID id, UUID companyId);
}
