package com.interviewiq.session.web;

import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.dto.CreateSessionRequest;
import com.interviewiq.session.dto.EvaluationReportResponse;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.dto.SetMeetUrlRequest;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Employer-facing session management endpoints. All require a valid employer JWT.
 *
 * <ul>
 *   <li>{@code POST  /api/v1/sessions}                  — create session, send invite email</li>
 *   <li>{@code GET   /api/v1/sessions}                  — list all sessions for company</li>
 *   <li>{@code GET   /api/v1/sessions?jobOpeningId=}     — list sessions for a specific job</li>
 *   <li>{@code GET   /api/v1/sessions?status=}           — list sessions filtered by status</li>
 *   <li>{@code GET   /api/v1/sessions/{id}}              — get session by ID</li>
 *   <li>{@code PATCH /api/v1/sessions/{id}/meet-url}     — set Google Meet URL (employer)</li>
 *   <li>{@code POST  /api/v1/sessions/{id}/cancel}       — cancel INVITED session</li>
 *   <li>{@code GET   /api/v1/sessions/{id}/evaluation}   — get AI evaluation report</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        return ApiResponse.created(sessionService.create(request));
    }

    /**
     * Lists sessions for the caller's company.
     * When {@code jobOpeningId} is supplied, results are scoped to that job.
     * When {@code status} is supplied (without jobOpeningId), results are filtered by status.
     * With no filters, all company sessions are returned ordered by creation date descending.
     */
    @GetMapping
    public ApiResponse<Page<SessionResponse>> list(
            @RequestParam(required = false) UUID jobOpeningId,
            @RequestParam(required = false) SessionStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        if (jobOpeningId != null) {
            return ApiResponse.ok(sessionService.listByJob(jobOpeningId, pageable));
        }
        return ApiResponse.ok(sessionService.listAll(status, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.get(id));
    }

    /**
     * PATCH /api/v1/sessions/{id}/meet-url
     * Employer sets the Google Meet URL for an INVITED or STARTED session.
     */
    @PatchMapping("/{id}/meet-url")
    public ApiResponse<SessionResponse> setMeetUrl(
            @PathVariable UUID id,
            @Valid @RequestBody SetMeetUrlRequest request) {
        return ApiResponse.ok(sessionService.setMeetUrl(id, request.googleMeetUrl()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<SessionResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.cancel(id));
    }

    @GetMapping("/{id}/evaluation")
    public ApiResponse<EvaluationReportResponse> getEvaluation(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.getEvaluation(id));
    }
}
