package com.interviewengine.scheduling.web;

import com.interviewengine.scheduling.dto.SchedulingDtos.AvailableTimesResponse;
import com.interviewengine.scheduling.dto.SchedulingDtos.BookRequest;
import com.interviewengine.scheduling.dto.SchedulingDtos.BookingResponse;
import com.interviewengine.scheduling.dto.SchedulingDtos.ReadinessResponse;
import com.interviewengine.scheduling.service.SchedulingService;
import com.interviewengine.shared.dto.ApiResponse;
import com.interviewengine.shared.security.SecurityContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Candidate-driven scheduling (PRD v2.1 §7.4, §11).
 *
 * <p>Four endpoints replace the entire employer slot-administration group, which
 * v2.1 deletes: {@code POST /jobs/{jobId}/slots} and everything around it is
 * gone, along with the {@code AvailabilitySlot} entity.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/candidate/scheduling/readiness}       — are questions ready?</li>
 *   <li>{@code GET  /api/v1/candidate/scheduling/available-times} — capacity-derived</li>
 *   <li>{@code POST /api/v1/candidate/scheduling/book}            — atomic bucket reservation</li>
 *   <li>{@code PUT  /api/v1/candidate/scheduling/book}            — reschedule</li>
 * </ul>
 *
 * <p>Sits on the candidate security chain, so every request is authenticated by
 * the candidate's session-scoped token. The session id comes from that token
 * rather than the URL — a candidate JWT "grants access to no other resource on
 * the platform" (§7.1.2), and taking the id from the path would invite exactly
 * the cross-session access that scoping is meant to prevent.
 */
@RestController
@RequestMapping("/api/v1/candidate/scheduling")
public class CandidateSchedulingController {

    private final SchedulingService schedulingService;

    public CandidateSchedulingController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    /**
     * The readiness gate (§7.4.3). The invite page polls this while questions
     * generate, showing "Preparing your interview — ready in about a minute".
     */
    @GetMapping("/readiness")
    public ApiResponse<ReadinessResponse> readiness() {
        return ApiResponse.ok(schedulingService.getReadiness(currentSessionId()));
    }

    /**
     * Bookable times, 24×7 and constrained only by platform capacity.
     *
     * @param from  optional window start; defaults to now plus the minimum notice
     * @param until optional window end; capped at the booking horizon
     */
    @GetMapping("/available-times")
    public ApiResponse<AvailableTimesResponse> availableTimes(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until) {

        return ApiResponse.ok(schedulingService.getAvailableTimes(currentSessionId(), from, until));
    }

    /** Books the interview. Capacity is taken atomically or the booking is refused. */
    @PostMapping("/book")
    public ApiResponse<BookingResponse> book(@Valid @RequestBody BookRequest request) {
        return ApiResponse.ok(schedulingService.book(currentSessionId(), request.startAt()));
    }

    /**
     * Moves an existing booking.
     *
     * <p>Releases the old buckets and occupies new ones. The invite token and the
     * ₹100 reservation are unaffected (§7.4.1) — which is why this is the same
     * service call as booking rather than a cancel-and-rebook.
     */
    @PutMapping("/book")
    public ApiResponse<BookingResponse> reschedule(@Valid @RequestBody BookRequest request) {
        return ApiResponse.ok(schedulingService.book(currentSessionId(), request.startAt()));
    }

    private UUID currentSessionId() {
        return SecurityContext.requireCandidate().sessionId();
    }
}
