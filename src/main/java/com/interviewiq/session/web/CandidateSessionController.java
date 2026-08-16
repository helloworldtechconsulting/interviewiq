package com.interviewiq.session.web;

import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate-facing session status endpoint. All requests must carry a valid invite token
 * (verified by the candidate filter chain in {@code CandidateTokenAuthFilter}).
 *
 * <p>The candidate's session and identity are derived entirely from the token — no
 * session ID or candidate ID in the path is needed or accepted.
 *
 * <p>For the full interview room lifecycle (init, start, complete, error) see
 * {@link CandidateInterviewController}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/candidate/session} — get the candidate's session details</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/candidate/session")
public class CandidateSessionController {

    private final SessionService sessionService;

    public CandidateSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * GET /api/v1/candidate/session
     * Returns session details for the authenticated candidate.
     */
    @GetMapping
    public ApiResponse<SessionResponse> getSession() {
        return ApiResponse.ok(sessionService.getCandidateSession());
    }
}
