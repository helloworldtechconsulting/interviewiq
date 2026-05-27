package com.interviewiq.session.web;

import com.interviewiq.session.dto.CompleteInterviewRequest;
import com.interviewiq.session.dto.InterviewInitResponse;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate-facing in-browser interview room endpoints (PRD v3).
 *
 * <p>All requests must carry a valid invite token (verified by
 * {@code CandidateTokenAuthFilter} in the candidate security chain).
 * The candidate's identity and session are derived entirely from the token.
 *
 * <h2>Interview lifecycle</h2>
 * <pre>
 * GET  /api/v1/candidate/interview/init     — load questions + recording upload URL
 * POST /api/v1/candidate/interview/start    — INVITED → STARTED (camera/mic confirmed)
 * POST /api/v1/candidate/interview/complete — STARTED → COMPLETED (all answers submitted)
 * POST /api/v1/candidate/interview/error    — any → ERROR (browser fatal error)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/candidate/interview")
public class CandidateInterviewController {

    private final SessionService sessionService;

    public CandidateInterviewController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * GET /api/v1/candidate/interview/init
     *
     * <p>Returns the session's questions, a pre-signed S3 PUT URL for the WebM
     * recording, and session metadata. The browser calls this once on load to
     * initialise the interview room UI.
     *
     * <p>If questions are not yet ready ({@code questionGenerationStatus != DONE})
     * the {@code questionsJson} field will be null — the browser should display a
     * "Preparing interview…" screen and poll until questions are available.
     */
    @GetMapping("/init")
    public ApiResponse<InterviewInitResponse> init() {
        return ApiResponse.ok(sessionService.initInterview());
    }

    /**
     * POST /api/v1/candidate/interview/start
     *
     * <p>Called when the candidate confirms their camera and microphone are working
     * and they are ready to begin. Transitions the session from INVITED → STARTED.
     *
     * <p>Idempotent: if the session is already STARTED (e.g. browser refresh), the
     * current state is returned without error.
     */
    @PostMapping("/start")
    public ApiResponse<SessionResponse> start() {
        return ApiResponse.ok(sessionService.startInterview());
    }

    /**
     * POST /api/v1/candidate/interview/complete
     *
     * <p>Called when the candidate has answered all questions. Accepts per-question
     * transcripts (captured by the browser's Web Speech API), optional anti-cheat
     * proctoring flags, and the S3 key of the uploaded video recording.
     *
     * <p>The service merges transcripts into {@code questionsJson}, settles billing,
     * and triggers the AI evaluation pipeline.
     */
    @PostMapping("/complete")
    public ApiResponse<SessionResponse> complete(@Valid @RequestBody CompleteInterviewRequest request) {
        return ApiResponse.ok(sessionService.completeInterview(request));
    }

    /**
     * POST /api/v1/candidate/interview/error
     *
     * <p>Called when a fatal browser error prevents the interview from continuing
     * (e.g. camera/microphone access revoked, network drop, WebRTC failure).
     * Transitions the session to ERROR and releases the billing reservation.
     *
     * <p>The {@code reason} query parameter is optional but helpful for debugging.
     */
    @PostMapping("/error")
    public ApiResponse<SessionResponse> error(
            @RequestParam(required = false, defaultValue = "Unknown browser error") String reason) {
        return ApiResponse.ok(sessionService.failInterview(reason));
    }
}
