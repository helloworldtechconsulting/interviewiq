package com.interviewiq.candidate;

import com.interviewiq.candidate.dto.CandidateResponse;
import com.interviewiq.candidate.dto.CreateCandidateRequest;
import com.interviewiq.common.ApiResponse;
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
@RequestMapping("/candidates")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
public class CandidateController {

    private final CandidateService candidateService;

    @PostMapping
    public ResponseEntity<ApiResponse<CandidateResponse>> createCandidate(
            @RequestParam UUID jobOpeningId,
            @Valid @RequestBody CreateCandidateRequest request,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        CandidateResponse response = candidateService.createCandidate(jobOpeningId, companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Candidate created successfully"));
    }

    @PostMapping("/{id}/upload-resume")
    public ResponseEntity<ApiResponse<CandidateResponse>> uploadResume(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {

        UUID companyId = extractCompanyIdFromAuth(auth);

        CandidateResponse response = candidateService.uploadResume(id, companyId, file);
        return ResponseEntity.ok(ApiResponse.success(response, "Resume uploaded successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CandidateResponse>> getCandidate(
            @PathVariable UUID id,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        CandidateResponse response = candidateService.getCandidate(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Candidate retrieved successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CandidateResponse>>> getCandidatesByCompany(
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        List<CandidateResponse> responses = candidateService.getCandidatesByCompany(companyId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Candidates retrieved successfully"));
    }

    @GetMapping("/job/{jobOpeningId}")
    public ResponseEntity<ApiResponse<List<CandidateResponse>>> getCandidatesByJobOpening(
            @PathVariable UUID jobOpeningId) {

        List<CandidateResponse> responses = candidateService.getCandidatesByJobOpening(jobOpeningId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Candidates retrieved successfully"));
    }

    private UUID extractCompanyIdFromAuth(Authentication auth) {
        // In production, extract from JWT token
        return UUID.randomUUID();
    }
}
