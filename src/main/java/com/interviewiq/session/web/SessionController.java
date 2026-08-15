package com.interviewiq.session.web;

import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.dto.CreateSessionRequest;
import com.interviewiq.session.dto.EvaluationReportResponse;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.service.SessionArtifactService;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Employer-facing session management endpoints. All require a valid employer JWT.
 *
 * <ul>
 *   <li>{@code POST  /api/v1/sessions}              — create session, send invite email</li>
 *   <li>{@code GET   /api/v1/sessions}              — list all sessions for company</li>
 *   <li>{@code GET   /api/v1/sessions?jobOpeningId=} — list sessions for a specific job</li>
 *   <li>{@code GET   /api/v1/sessions?status=}       — list sessions filtered by status</li>
 *   <li>{@code GET   /api/v1/sessions/{id}}          — get session by ID</li>
 *   <li>{@code POST  /api/v1/sessions/{id}/cancel}   — cancel INVITED session</li>
 *   <li>{@code POST  /api/v1/sessions/{id}/reinvite} — resend the invite, or start a replacement</li>
 *   <li>{@code GET   /api/v1/sessions/{id}/evaluation} — get AI evaluation report</li>
 *   <li>{@code GET   /api/v1/sessions/{id}/recording}  — short-lived playback URL</li>
 *   <li>{@code GET   /api/v1/sessions/{id}/transcript} — plain-text transcript download</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionArtifactService artifactService;

    public SessionController(SessionService sessionService,
                             SessionArtifactService artifactService) {
        this.sessionService  = sessionService;
        this.artifactService = artifactService;
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

    @PostMapping("/{id}/cancel")
    public ApiResponse<SessionResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.cancel(id));
    }

    /**
     * POST /api/v1/sessions/{id}/reinvite
     *
     * <p>Resends the invite for a session that is still live, or creates a
     * replacement session when the previous one expired or was cancelled. The
     * response is the session the candidate should now be working from — which is
     * a <em>different</em> session in the replacement case, so clients should use
     * the returned id rather than the one they sent.
     */
    @PostMapping("/{id}/reinvite")
    public ApiResponse<SessionResponse> reinvite(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.reinvite(id));
    }

    /**
     * GET /api/v1/sessions/{id}/recording
     *
     * <p>Returns {@code {"recordingUrl": "..."}} — a 15-minute playback URL, not
     * the video itself. The recording never passes through the application.
     */
    @GetMapping("/{id}/recording")
    public ApiResponse<Map<String, String>> getRecording(@PathVariable UUID id) {
        return ApiResponse.ok(Map.of("recordingUrl", artifactService.recordingUrl(id)));
    }

    /**
     * GET /api/v1/sessions/{id}/transcript
     *
     * <p>Returns the transcript as a downloadable text file rather than JSON — a
     * recruiter forwarding this to a hiring manager wants an attachment, not a
     * quoted string they have to unescape.
     */
    @GetMapping(value = "/{id}/transcript", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getTranscript(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifactService.transcriptFilename(id) + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(artifactService.transcript(id));
    }

    @GetMapping("/{id}/evaluation")
    public ApiResponse<EvaluationReportResponse> getEvaluation(@PathVariable UUID id) {
        return ApiResponse.ok(sessionService.getEvaluation(id));
    }
}
