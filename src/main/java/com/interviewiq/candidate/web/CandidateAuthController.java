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

/**
 * Candidate Google identity verification endpoint.
 *
 * <p>Sits in the candidate security chain ({@code /api/v1/candidate/**}),
 * so every request must already carry a valid HMAC invite token.
 * The {@link CandidatePrincipal} is already resolved by the time this
 * controller is called — no additional authentication is needed.
 *
 * <h2>Purpose</h2>
 * <p>This endpoint allows a candidate to prove their Google identity
 * during the interview setup phase. The backend:
 * <ol>
 *   <li>Verifies the Google ID token.</li>
 *   <li>Stores {@code googleSubject}, {@code googleEmail}, and sets
 *       {@code googleVerified = true} on the {@link Candidate} row.</li>
 * </ol>
 *
 * <p>The frontend checks {@code initData.googleVerified} (from
 * {@code GET /api/v1/candidate/interview/init}) to decide whether to
 * show the Google sign-in step before the interview setup phase.
 *
 * <h2>Idempotency</h2>
 * <p>If the candidate has already verified via Google, calling this
 * endpoint again with the same or a different token simply updates the
 * stored Google identity. This is intentional — it allows the candidate
 * to re-verify after a page refresh without any UX friction.
 */
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

    /**
     * POST /api/v1/candidate/auth/google
     *
     * <p>Verifies the Google ID token and records the identity on the candidate.
     *
     * @param principal the candidate principal extracted from the invite token
     * @param request   the Google ID token from the frontend
     * @return success response with no payload
     */
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
