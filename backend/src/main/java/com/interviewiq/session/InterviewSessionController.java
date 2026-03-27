package com.interviewiq.session;

import com.interviewiq.common.ApiResponse;
import com.interviewiq.session.dto.InterviewSessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Slf4j
public class InterviewSessionController {

    private final InterviewSessionService interviewSessionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> createSession(
            @RequestParam UUID candidateId,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        InterviewSessionResponse response = interviewSessionService.createSession(candidateId, companyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Interview session created successfully"));
    }

    @GetMapping("/{token}/verify")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> verifyInvite(
            @PathVariable String token) {

        InterviewSessionResponse response = interviewSessionService.getSessionByToken(token);
        return ResponseEntity.ok(ApiResponse.success(response, "Interview invite verified"));
    }

    @PostMapping("/{token}/accept")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> acceptInvite(
            @PathVariable String token) {

        InterviewSessionResponse response = interviewSessionService.acceptInvite(token);
        return ResponseEntity.ok(ApiResponse.success(response, "Interview invite accepted"));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> startSession(
            @PathVariable UUID id,
            @RequestParam(required = false) String token) {

        // Validate token if provided (for candidate)
        if (token != null) {
            interviewSessionService.getSessionByToken(token);
        }

        // For now, accept any companyId (in production, extract from JWT)
        UUID companyId = UUID.randomUUID();

        InterviewSessionResponse response = interviewSessionService.startSession(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Interview session started"));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> completeSession(
            @PathVariable UUID id) {

        UUID companyId = UUID.randomUUID();

        InterviewSessionResponse response = interviewSessionService.completeSession(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Interview session completed"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
    public ResponseEntity<ApiResponse<List<InterviewSessionResponse>>> getSessionsByCompany(
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        List<InterviewSessionResponse> responses = interviewSessionService.getSessionsByCompany(companyId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Sessions retrieved successfully"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
    public ResponseEntity<ApiResponse<InterviewSessionResponse>> getSession(
            @PathVariable UUID id,
            Authentication auth) {

        UUID companyId = extractCompanyIdFromAuth(auth);

        InterviewSessionResponse response = interviewSessionService.getSession(id, companyId);
        return ResponseEntity.ok(ApiResponse.success(response, "Session retrieved successfully"));
    }

    @GetMapping("/candidate/{candidateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERVIEWER')")
    public ResponseEntity<ApiResponse<List<InterviewSessionResponse>>> getSessionsByCandidate(
            @PathVariable UUID candidateId) {

        List<InterviewSessionResponse> responses = interviewSessionService.getSessionsByCandidate(candidateId);
        return ResponseEntity.ok(ApiResponse.success(responses, "Sessions retrieved successfully"));
    }

    private UUID extractCompanyIdFromAuth(Authentication auth) {
        // In production, extract from JWT token
        return UUID.randomUUID();
    }
}
