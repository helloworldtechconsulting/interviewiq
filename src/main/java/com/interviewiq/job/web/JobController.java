package com.interviewiq.job.web;

import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.job.dto.CreateJobRequest;
import com.interviewiq.job.dto.JdUploadUrlResponse;
import com.interviewiq.job.dto.JobResponse;
import com.interviewiq.job.dto.UpdateJobRequest;
import com.interviewiq.job.service.JobService;
import com.interviewiq.shared.dto.ApiResponse;
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
 * Job opening management endpoints. All require a valid employer JWT.
 *
 * <ul>
 *   <li>{@code POST   /api/v1/jobs}                         — create job</li>
 *   <li>{@code GET    /api/v1/jobs}                         — paginated list</li>
 *   <li>{@code GET    /api/v1/jobs/{id}}                    — get by ID</li>
 *   <li>{@code PATCH  /api/v1/jobs/{id}}                    — partial update</li>
 *   <li>{@code DELETE /api/v1/jobs/{id}}                    — soft-delete (→ CLOSED)</li>
 *   <li>{@code GET    /api/v1/jobs/{id}/jd-upload-url}      — presigned S3 PUT URL</li>
 *   <li>{@code POST   /api/v1/jobs/{id}/jd-upload-confirm}  — confirm JD upload</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Validated
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        return ApiResponse.created(jobService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<JobResponse>> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(jobService.list(status, search, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(jobService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<JobResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateJobRequest request) {
        return ApiResponse.ok(jobService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        jobService.delete(id);
    }

    /**
     * GET /api/v1/jobs/{id}/jd-upload-url?contentType=application/pdf
     * Returns a presigned S3 PUT URL for uploading the JD document.
     */
    @GetMapping("/{id}/jd-upload-url")
    public ApiResponse<JdUploadUrlResponse> getJdUploadUrl(
            @PathVariable UUID id,
            @RequestParam
            @NotBlank(message = "contentType is required.")
            String contentType) {
        return ApiResponse.ok(jobService.generateJdUploadUrl(id, contentType));
    }

    /**
     * POST /api/v1/jobs/{id}/jd-upload-confirm
     * Call after the client has uploaded the JD to S3 to register the object key.
     */
    @PostMapping("/{id}/jd-upload-confirm")
    public ApiResponse<JobResponse> confirmJdUpload(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(jobService.confirmJdUploaded(id, body.get("objectKey")));
    }
}
