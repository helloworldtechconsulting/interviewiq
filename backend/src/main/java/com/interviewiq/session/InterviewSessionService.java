package com.interviewiq.session;

import com.interviewiq.candidate.Candidate;
import com.interviewiq.candidate.CandidateRepository;
import com.interviewiq.common.BadRequestException;
import com.interviewiq.common.ResourceNotFoundException;
import com.interviewiq.email.EmailService;
import com.interviewiq.job.JobOpening;
import com.interviewiq.job.JobOpeningRepository;
import com.interviewiq.session.dto.InterviewSessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewSessionService {

    private final InterviewSessionRepository sessionRepository;
    private final CandidateRepository candidateRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final EmailService emailService;

    @Value("${app.interview.invite-token-expiry-hours:72}")
    private int inviteTokenExpiryHours;

    @Transactional
    public InterviewSessionResponse createSession(UUID candidateId, UUID companyId) {
        Candidate candidate = candidateRepository.findByIdAndCompanyId(candidateId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found"));

        JobOpening jobOpening = jobOpeningRepository.findById(candidate.getJobOpeningId())
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        String inviteToken = generateInviteToken(candidateId, candidate.getEmail());
        LocalDateTime expiresAt = LocalDateTime.now().plus(inviteTokenExpiryHours, ChronoUnit.HOURS);

        InterviewSession session = InterviewSession.builder()
                .candidateId(candidateId)
                .jobOpeningId(jobOpening.getId())
                .companyId(companyId)
                .inviteToken(inviteToken)
                .inviteExpiresAt(expiresAt)
                .status(SessionStatus.INVITED)
                .build();

        session = sessionRepository.save(session);

        sendInviteEmail(candidate, jobOpening, inviteToken);
        triggerQuestionGeneration(session.getId(), jobOpening.getId(), candidateId);

        log.info("Interview session created: {} for candidate: {}", session.getId(), candidateId);

        return mapToResponse(session);
    }

    public InterviewSessionResponse getSessionByToken(String inviteToken) {
        InterviewSession session = sessionRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        if (session.getInviteExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Interview invite has expired");
        }

        return mapToResponse(session);
    }

    @Transactional
    public InterviewSessionResponse acceptInvite(String inviteToken) {
        InterviewSession session = sessionRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        if (session.getInviteExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Interview invite has expired");
        }

        session.setStatus(SessionStatus.ACCEPTED);
        session = sessionRepository.save(session);

        log.info("Interview session accepted: {}", session.getId());

        return mapToResponse(session);
    }

    @Transactional
    public InterviewSessionResponse startSession(UUID sessionId, UUID companyId) {
        InterviewSession session = sessionRepository.findByIdAndCompanyId(sessionId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        if (!session.getStatus().equals(SessionStatus.ACCEPTED) && !session.getStatus().equals(SessionStatus.INVITED)) {
            throw new BadRequestException("Cannot start session in current status: " + session.getStatus());
        }

        session.setStatus(SessionStatus.STARTED);
        session.setStartedAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        log.info("Interview session started: {}", sessionId);

        return mapToResponse(session);
    }

    @Transactional
    public InterviewSessionResponse completeSession(UUID sessionId, UUID companyId) {
        InterviewSession session = sessionRepository.findByIdAndCompanyId(sessionId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));

        session.setStatus(SessionStatus.COMPLETED);
        session.setEndedAt(LocalDateTime.now());

        if (session.getStartedAt() != null) {
            long durationSeconds = ChronoUnit.SECONDS.between(session.getStartedAt(), session.getEndedAt());
            session.setDurationSeconds((int) durationSeconds);
        }

        session = sessionRepository.save(session);

        log.info("Interview session completed: {}", sessionId);

        triggerEvaluation(sessionId);
        notifyCompletion(session);

        return mapToResponse(session);
    }

    public List<InterviewSessionResponse> getSessionsByCandidate(UUID candidateId) {
        return sessionRepository.findByCandidateId(candidateId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<InterviewSessionResponse> getSessionsByCompany(UUID companyId) {
        return sessionRepository.findByCompanyId(companyId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<InterviewSessionResponse> getSessionsByJobOpening(UUID jobOpeningId) {
        return sessionRepository.findByJobOpeningId(jobOpeningId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public InterviewSessionResponse getSession(UUID sessionId, UUID companyId) {
        InterviewSession session = sessionRepository.findByIdAndCompanyId(sessionId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview session not found"));
        return mapToResponse(session);
    }

    private String generateInviteToken(UUID candidateId, String email) {
        String data = candidateId + ":" + email + ":" + System.currentTimeMillis();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes());
    }

    @Async
    protected void sendInviteEmail(Candidate candidate, JobOpening jobOpening, String inviteToken) {
        try {
            emailService.sendInterviewInvite(
                    candidate.getEmail(),
                    candidate.getName(),
                    jobOpening.getTitle(),
                    inviteToken
            );
        } catch (Exception e) {
            log.error("Failed to send interview invite email to: {}", candidate.getEmail(), e);
        }
    }

    @Async
    protected void triggerQuestionGeneration(UUID sessionId, UUID jobOpeningId, UUID candidateId) {
        // This will be called by QuestionGenerationService
        log.info("Triggering question generation for session: {}", sessionId);
    }

    @Async
    protected void triggerEvaluation(UUID sessionId) {
        // This will be called by EvaluationService
        log.info("Triggering evaluation for session: {}", sessionId);
    }

    @Async
    protected void notifyCompletion(InterviewSession session) {
        try {
            Candidate candidate = candidateRepository.findById(session.getCandidateId())
                    .orElse(null);
            JobOpening jobOpening = jobOpeningRepository.findById(session.getJobOpeningId())
                    .orElse(null);

            if (candidate != null && jobOpening != null) {
                emailService.sendInterviewCompleted(
                        candidate.getEmail(),
                        candidate.getName(),
                        jobOpening.getTitle()
                );
            }
        } catch (Exception e) {
            log.error("Failed to send completion email for session: {}", session.getId(), e);
        }
    }

    private InterviewSessionResponse mapToResponse(InterviewSession session) {
        return new InterviewSessionResponse(
                session.getId(),
                session.getCandidateId(),
                session.getJobOpeningId(),
                session.getInviteToken(),
                session.getStatus(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getDurationSeconds(),
                session.getOverallScore(),
                session.getRecommendation(),
                session.getEvaluationSummary(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
