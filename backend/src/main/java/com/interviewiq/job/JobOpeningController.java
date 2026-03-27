package com.interviewiq.job;

import com.interviewiq.common.ApiResponse;
import com.interviewiq.job.dto.CreateJobOpeningRequest;
import com.interviewiq.job.dto.JobOpeningResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/job-openings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
public class JobOpeningController {

    private final JobOpeningService jobOpeningService;

    @PostMapping
    public ResponseEntity<ApiResponse<JobOpeningResponse>> createJobOpening(
            @Valid @RequestBody CreateJobOpeningRequest request,
            Authentication auth) {

        UUID userId = extractUserIdFromAuth(auth);
        UUID companyId = extractCompanyIdFromAuth(auth);

        JobOpeningResponse response = jobOpeningService.createJobOpening(companyId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Job opening created successfully"));
    }

    @PostMapping("/{id}/upload-jd")
    public ResponseEntity<ApiResponse<JobOpeningResponse>> uploadJobDescription(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {

        UUID companyId = extractCompanyIdFromAuth(auth);

        JobOpeningResponse response = jobOpeningService.uploadJobDescription(id, companyId, file);
        return ResponseEntity.ok(ApiResponse.success(response, "Job description uploaded successfully"));
    }

    @PatchMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<JobOpeningResponse>> publishJobOpening(
            @PathVariable UUID id,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        JobOpeningResponse response = jobOpeningService.publishJobOpening(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Job opening published successfully"));
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<ApiResponse<JobOpeningResponse>> closeJobOpening(
            @PathVariable UUID id,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        JobOpeningResponse response = jobOpeningService.closeJobOpening(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Job opening closed successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<JobOpeningResponse>>> getJobOpenings(
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        List<JobOpeningResponse> responses = jobOpeningService.getJobOpenings(companyId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Job openings retrieved successfully"));
    }

    @GetMapping("/active")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<List<JobOpeningResponse>>> getActiveJobOpenings(
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        List<JobOpeningResponse> responses = jobOpeningService.getActiveJobOpenings(companyId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Active job openings retrieved successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobOpeningResponse>> getJobOpening(
            @PathVariable UUID id,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        JobOpeningResponse response = jobOpeningService.getJobOpening(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Job opening retrieved successfully"));
    }

    private UUID extractUserIdFromAuth(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails user) {
            // In production, extract from JWT token
            return UUID.randomUUID();
        }
        return UUID.randomUUID();
    }

    private UUID extractCompanyIdFromAuth(Authentication auth) {
        // In production, extract from JWT token
        return UUID.randomUUID();
    }
}
