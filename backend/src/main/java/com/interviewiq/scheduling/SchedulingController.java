package com.interviewiq.scheduling;

import com.interviewiq.auth.User;
import com.interviewiq.common.ApiResponse;
import com.interviewiq.scheduling.dto.BookSlotRequest;
import com.interviewiq.scheduling.dto.CreateSlotRequest;
import com.interviewiq.scheduling.dto.SlotResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Slf4j
public class SchedulingController {

    private final SchedulingService schedulingService;

    @PostMapping("/{jobId}/slots")
    public ResponseEntity<ApiResponse<List<SlotResponse>>> createSlots(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody List<CreateSlotRequest> requests) {
        log.info("Creating {} availability slots for job: {} by company: {}", requests.size(), jobId, user.getCompanyId());
        var slots = schedulingService.createSlots(jobId, requests);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(slots, "Availability slots created successfully"));
    }

    @GetMapping("/{jobId}/slots")
    public ResponseEntity<ApiResponse<List<SlotResponse>>> getSlots(
            @PathVariable UUID jobId,
            @RequestParam(required = false, defaultValue = "false") boolean allSlots,
            @AuthenticationPrincipal User user) {
        log.info("Fetching slots for job: {}", jobId);
        List<SlotResponse> slots;
        if (allSlots) {
            slots = schedulingService.getAllSlots(jobId);
        } else {
            slots = schedulingService.getAvailableSlots(jobId);
        }
        return ResponseEntity.ok(ApiResponse.success(slots, "Slots retrieved successfully"));
    }

    @PostMapping("/{jobId}/slots/search")
    public ResponseEntity<ApiResponse<List<SlotResponse>>> getAvailableSlots(
            @PathVariable UUID jobId) {
        log.info("Searching available slots for job: {}", jobId);
        var slots = schedulingService.getAvailableSlots(jobId);
        return ResponseEntity.ok(ApiResponse.success(slots, "Available slots retrieved successfully"));
    }

    @PostMapping("/sessions/{sessionId}/book")
    public ResponseEntity<ApiResponse<SlotResponse>> bookSlot(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BookSlotRequest request) {
        log.info("Booking slot {} for session: {}", request.slotId(), sessionId);
        var slot = schedulingService.bookSlot(sessionId, request.slotId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(slot, "Slot booked successfully"));
    }

    @GetMapping("/sessions/{sessionId}/schedule")
    public ResponseEntity<ApiResponse<SlotResponse>> getSchedule(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal User user) {
        log.info("Fetching schedule for session: {}", sessionId);
        var slot = schedulingService.getAvailableSlots(UUID.randomUUID()); // Placeholder: fetch actual slot from session
        return ResponseEntity.ok(ApiResponse.success(slot.isEmpty() ? null : slot.get(0), "Schedule retrieved successfully"));
    }

    @DeleteMapping("/sessions/{sessionId}/book")
    public ResponseEntity<ApiResponse<String>> cancelSlotBooking(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal User user) {
        log.info("Cancelling slot booking for session: {}", sessionId);
        schedulingService.cancelSlotBooking(sessionId);
        return ResponseEntity.ok(ApiResponse.success("Slot booking cancelled successfully"));
    }
}
