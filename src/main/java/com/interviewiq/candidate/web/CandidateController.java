package com.interviewiq.candidate.web;

import com.interviewiq.candidate.dto.CandidateResponse;
import com.interviewiq.candidate.dto.CreateCandidateRequest;
import com.interviewiq.candidate.dto.ResumeUploadUrlResponse;
import com.interviewiq.candidate.service.CandidateService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Candidate management endpoints. All require a valid employer JWT.
 *
 * <ul>
 *   <li>{@code POST  /api/v1/candidates}                              — create candidate</li>
 *   <li>{@code GET   /api/v1/candidates}                              — list all for company</li>
 *   <li>{@code GET   /api/v1/candidates?jobOpeningId=...}             — list by job (optional filter)</li>
 *   <li>{@code GET   /api/v1/candidates/{id}}                         — get by ID</li>
 *   <li>{@code GET   /api/v1/candidates/{id}/resume-upload-url}       — presigned PUT URL</li>
 *   <li>{@code POST  /api/v1/candidates/{id}/resume-upload-confirm}   — confirm upload</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/candidates")
@Validated
public class CandidateController {

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CandidateResponse> create(@Valid @RequestBody CreateCandidateRequest request) {
        return ApiResponse.created(candidateService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<CandidateResponse>> list(
            @RequestParam(required = false) UUID jobOpeningId,
            @PageableDefault(size = 20) Pageable pageable) {
        if (jobOpeningId != null) {
            return ApiResponse.ok(candidateService.listByJob(jobOpeningId, pageable));
        }
        return ApiResponse.ok(candidateService.listAll(pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<CandidateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(candidateService.get(id));
    }

    /**
     * GET /api/v1/candidates/{id}/resume-upload-url?contentType=application/pdf
     */
    @GetMapping("/{id}/resume-upload-url")
    public ApiResponse<ResumeUploadUrlResponse> getResumeUploadUrl(
            @PathVariable UUID id,
            @RequestParam
            @NotBlank(message = "contentType is required.")
            String contentType) {
        return ApiResponse.ok(candidateService.generateResumeUploadUrl(id, contentType));
    }

    /**
     * POST /api/v1/candidates/{id}/resume-upload-confirm
     */
    @PostMapping("/{id}/resume-upload-confirm")
    public ApiResponse<CandidateResponse> confirmResumeUpload(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(candidateService.confirmResumeUploaded(id, body.get("objectKey")));
    }
}
