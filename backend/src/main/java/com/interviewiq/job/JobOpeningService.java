package com.interviewiq.job;

import com.interviewiq.common.ResourceNotFoundException;
import com.interviewiq.common.UnauthorizedException;
import com.interviewiq.job.dto.CreateJobOpeningRequest;
import com.interviewiq.job.dto.JobOpeningResponse;
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
public class JobOpeningService {

    private final JobOpeningRepository jobOpeningRepository;
    private final StorageService storageService;

    @Transactional
    public JobOpeningResponse createJobOpening(
            UUID companyId,
            UUID userId,
            CreateJobOpeningRequest request) {

        JobOpening jobOpening = JobOpening.builder()
                .companyId(companyId)
                .title(request.title())
                .department(request.department())
                .locationType(request.locationType() != null ? request.locationType() : LocationType.REMOTE)
                .employmentType(request.employmentType() != null ? request.employmentType() : EmploymentType.FULL_TIME)
                .description(request.description())
                .status(JobStatus.DRAFT)
                .createdBy(userId)
                .build();

        jobOpening = jobOpeningRepository.save(jobOpening);
        log.info("Job opening created: {} for company: {}", jobOpening.getId(), companyId);

        return mapToResponse(jobOpening);
    }

    @Transactional
    public JobOpeningResponse uploadJobDescription(
            UUID jobOpeningId,
            UUID companyId,
            MultipartFile jdFile) throws IOException {

        JobOpening jobOpening = jobOpeningRepository.findByIdAndCompanyId(jobOpeningId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        String fileName = "jd/" + jobOpeningId + "/" + jdFile.getOriginalFilename();
        String gcsPath = storageService.uploadFile(fileName, jdFile);

        String extractedText = storageService.extractTextFromFile(jdFile);

        jobOpening.setJdGcsPath(gcsPath);
        jobOpening.setJdExtractedText(extractedText);
        jobOpening = jobOpeningRepository.save(jobOpening);

        log.info("Job description uploaded for job opening: {}", jobOpeningId);

        return mapToResponse(jobOpening);
    }

    @Transactional
    public JobOpeningResponse publishJobOpening(UUID jobOpeningId, UUID companyId) {
        JobOpening jobOpening = jobOpeningRepository.findByIdAndCompanyId(jobOpeningId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        jobOpening.setStatus(JobStatus.ACTIVE);
        jobOpening = jobOpeningRepository.save(jobOpening);

        log.info("Job opening published: {}", jobOpeningId);

        return mapToResponse(jobOpening);
    }

    @Transactional
    public JobOpeningResponse closeJobOpening(UUID jobOpeningId, UUID companyId) {
        JobOpening jobOpening = jobOpeningRepository.findByIdAndCompanyId(jobOpeningId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));

        jobOpening.setStatus(JobStatus.CLOSED);
        jobOpening = jobOpeningRepository.save(jobOpening);

        log.info("Job opening closed: {}", jobOpeningId);

        return mapToResponse(jobOpening);
    }

    public List<JobOpeningResponse> getJobOpenings(UUID companyId) {
        return jobOpeningRepository.findByCompanyId(companyId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<JobOpeningResponse> getActiveJobOpenings(UUID companyId) {
        return jobOpeningRepository.findByCompanyIdAndStatus(companyId, JobStatus.ACTIVE)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public JobOpeningResponse getJobOpening(UUID jobOpeningId, UUID companyId) {
        JobOpening jobOpening = jobOpeningRepository.findByIdAndCompanyId(jobOpeningId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Job opening not found"));
        return mapToResponse(jobOpening);
    }

    private JobOpeningResponse mapToResponse(JobOpening jobOpening) {
        return new JobOpeningResponse(
                jobOpening.getId(),
                jobOpening.getTitle(),
                jobOpening.getDepartment(),
                jobOpening.getLocationType(),
                jobOpening.getEmploymentType(),
                jobOpening.getDescription(),
                jobOpening.getStatus(),
                jobOpening.getCreatedAt(),
                jobOpening.getUpdatedAt()
        );
    }
}
