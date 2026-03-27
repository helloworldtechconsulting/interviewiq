package com.interviewiq.candidate;

import com.interviewiq.candidate.dto.CandidateResponse;
import com.interviewiq.candidate.dto.CreateCandidateRequest;
import com.interviewiq.common.BadRequestException;
import com.interviewiq.common.ResourceNotFoundException;
import com.interviewiq.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final StorageService storageService;

    @Transactional
    public CandidateResponse createCandidate(
            UUID jobOpeningId,
            UUID companyId,
            CreateCandidateRequest request) {

        if (candidateRepository.findByJobOpeningIdAndEmail(jobOpeningId, request.email()).isPresent()) {
            throw new BadRequestException("Candidate with this email already exists for this job");
        }

        Candidate candidate = Candidate.builder()
                .companyId(companyId)
                .jobOpeningId(jobOpeningId)
                .name(request.name())
                .email(request.email())
                .phone(request.phone())
                .build();

        candidate = candidateRepository.save(candidate);
        log.info("Candidate created: {} for job opening: {}", candidate.getId(), jobOpeningId);

        return mapToResponse(candidate);
    }

    @Transactional
    public CandidateResponse uploadResume(
            UUID candidateId,
            UUID companyId,
            MultipartFile resumeFile) throws IOException {

        Candidate candidate = candidateRepository.findByIdAndCompanyId(candidateId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found"));

        String fileName = "resumes/" + candidateId + "/" + resumeFile.getOriginalFilename();
        String gcsPath = storageService.uploadFile(fileName, resumeFile);

        String extractedText = storageService.extractTextFromFile(resumeFile);

        candidate.setResumeGcsPath(gcsPath);
        candidate.setResumeExtractedText(extractedText);
        candidate = candidateRepository.save(candidate);

        log.info("Resume uploaded for candidate: {}", candidateId);

        return mapToResponse(candidate);
    }

    public CandidateResponse getCandidate(UUID candidateId, UUID companyId) {
        Candidate candidate = candidateRepository.findByIdAndCompanyId(candidateId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found"));
        return mapToResponse(candidate);
    }

    public List<CandidateResponse> getCandidatesByJobOpening(UUID jobOpeningId) {
        return candidateRepository.findByJobOpeningId(jobOpeningId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<CandidateResponse> getCandidatesByCompany(UUID companyId) {
        return candidateRepository.findByCompanyId(companyId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private CandidateResponse mapToResponse(Candidate candidate) {
        return new CandidateResponse(
                candidate.getId(),
                candidate.getName(),
                candidate.getEmail(),
                candidate.getPhone(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }
}
