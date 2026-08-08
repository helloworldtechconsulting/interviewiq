package com.interviewiq.candidate.web;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.interviewiq.auth.dto.GoogleAuthRequest;
import com.interviewiq.auth.service.GoogleOAuthService;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.security.CandidatePrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidate/auth")
public class CandidateAuthController {

    private static final Logger log = LoggerFactory.getLogger(CandidateAuthController.class);

    private final GoogleOAuthService  googleOAuthService;
    private final CandidateRepository candidateRepository;

    public CandidateAuthController(GoogleOAuthService googleOAuthService,
                                   CandidateRepository candidateRepository) {
        this.googleOAuthService  = googleOAuthService;
        this.candidateRepository = candidateRepository;
    }

    @PostMapping("/google")
    @Transactional
    public ApiResponse<Void> verifyGoogleIdentity(
            @AuthenticationPrincipal CandidatePrincipal principal,
            @Valid @RequestBody GoogleAuthRequest request) {

        Payload payload = googleOAuthService.verify(request.idToken());

        String googleSubject = payload.getSubject();
        String googleEmail   = payload.getEmail();

        Candidate candidate = candidateRepository
                .findByCompanyIdAndId(principal.companyId(), principal.candidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found."));

        candidate.setGoogleSubject(googleSubject);
        candidate.setGoogleEmail(googleEmail);
        candidate.setGoogleVerified(true);
        candidateRepository.save(candidate);

        log.info("Candidate Google identity verified: candidateId={} googleEmail={}",
                candidate.getId(), googleEmail);

        return ApiResponse.ok();
    }
}
