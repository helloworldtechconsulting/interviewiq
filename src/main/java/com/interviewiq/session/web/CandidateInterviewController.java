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

@RestController
@RequestMapping("/api/v1/candidate/interview")
public class CandidateInterviewController {

    private final SessionService sessionService;

    public CandidateInterviewController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/init")
    public ApiResponse<InterviewInitResponse> init() {
        return ApiResponse.ok(sessionService.initInterview());
    }

    @PostMapping("/start")
    public ApiResponse<SessionResponse> start() {
        return ApiResponse.ok(sessionService.startInterview());
    }

    @PostMapping("/complete")
    public ApiResponse<SessionResponse> complete(@Valid @RequestBody CompleteInterviewRequest request) {
        return ApiResponse.ok(sessionService.completeInterview(request));
    }

    @PostMapping("/error")
    public ApiResponse<SessionResponse> error(
            @RequestParam(required = false, defaultValue = "Unknown browser error") String reason) {
        return ApiResponse.ok(sessionService.failInterview(reason));
    }
}
