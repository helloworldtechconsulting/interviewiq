package com.interviewengine.candidate.web;

import com.interviewengine.candidate.dto.CandidateResponse;
import com.interviewengine.candidate.dto.CreateCandidateRequest;
import com.interviewengine.candidate.dto.ResumeUploadUrlResponse;
import com.interviewengine.candidate.dto.UpdateCandidateRequest;
import com.interviewengine.candidate.service.CandidateService;
import com.interviewengine.shared.domain.PipelineStatus;
import com.interviewengine.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    /**
     * GET /api/v1/candidates?jobOpeningId=…&amp;resumeStatus=…&amp;search=…
     *
     * <p>All three filters are optional and are applied in the database. The
     * {@code search} term matches name or email; it used to be applied in the
     * browser over the current page only, which meant a candidate on page two was
     * unfindable.
     */
    @GetMapping
    public ApiResponse<Page<CandidateResponse>> list(
            @RequestParam(required = false) UUID jobOpeningId,
            @RequestParam(required = false) PipelineStatus resumeStatus,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(
                candidateService.search(jobOpeningId, resumeStatus, search, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<CandidateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(candidateService.get(id));
    }

    /**
     * PATCH /api/v1/candidates/{id}
     *
     * <p>PATCH rather than PUT because the request is a partial update — omitted
     * fields mean "leave unchanged", not "clear". Matches the company profile
     * endpoint and what the frontend client already sends.
     *
     * <p>Refused once the candidate has been invited — see
     * {@code CandidateService.update} for why editing after an invite is worse
     * than refusing it.
     */
    @PatchMapping("/{id}")
    public ApiResponse<CandidateResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCandidateRequest request) {
        return ApiResponse.ok(candidateService.update(id, request));
    }

    /**
     * DELETE /api/v1/candidates/{id}
     *
     * <p>Refused when a completed interview exists, and also when one is still in
     * flight holding a wallet reservation.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        candidateService.delete(id);
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
