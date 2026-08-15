package com.interviewiq.session.web;

import com.interviewiq.session.dto.ProctoringEventBatchRequest;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.service.InterviewRoomService;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.shared.security.CandidatePrincipal;
import com.interviewiq.shared.security.SecurityContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
 *   <li>{@code GET  /api/v1/candidate/session}        — get the candidate's session details</li>
 *   <li>{@code POST /api/v1/candidate/session/events} — replay buffered proctoring events</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/candidate/session")
public class CandidateSessionController {

    private final SessionService      sessionService;
    private final InterviewRoomService roomService;

    public CandidateSessionController(SessionService sessionService,
                                      InterviewRoomService roomService) {
        this.sessionService = sessionService;
        this.roomService    = roomService;
    }

    /**
     * GET /api/v1/candidate/session
     * Returns session details for the authenticated candidate.
     */
    @GetMapping
    public ApiResponse<SessionResponse> getSession() {
        return ApiResponse.ok(sessionService.getCandidateSession());
    }

    /**
     * POST /api/v1/candidate/session/events — proctoring events over REST.
     *
     * <p>The WebSocket carries these during a healthy interview. This is the
     * path for when it is not healthy: the browser buffers events while
     * disconnected and posts the backlog on reconnect. Without it, proctoring
     * data goes missing precisely on the flaky connections that generate the
     * most of it, which biases the record in a direction no reader can detect.
     *
     * <p>The session comes from the invite token, never the request body —
     * the same rule the rest of this controller follows. A body-supplied
     * session ID would let any valid candidate token write proctoring events
     * onto somebody else's interview.
     *
     * <p>Replays are deduplicated on (session, type, timestamp), so a client
     * that cannot tell which events already got through can safely re-send its
     * whole buffer.
     */
    @PostMapping("/events")
    public ApiResponse<Map<String, Integer>> submitEvents(
            @Valid @RequestBody ProctoringEventBatchRequest request) {

        CandidatePrincipal principal = SecurityContext.requireCandidate();
        int recorded = roomService.recordProctoringEvents(
                principal.sessionId(),
                request.events().stream()
                        .map(e -> new InterviewRoomService.ProctoringEventSubmission(e.type(), e.occurredAt()))
                        .toList());

        return ApiResponse.ok(Map.of(
                "submitted", request.events().size(),
                "recorded", recorded));
    }
}
