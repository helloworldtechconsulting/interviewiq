package com.interviewiq.session.web;

import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.storage.domain.StorageObjectType;
import com.interviewiq.storage.domain.UploadKind;
import com.interviewiq.storage.service.StorageObjectRecorder;
import com.interviewiq.storage.service.StorageService;
import com.interviewiq.storage.service.UploadKeyService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Recording upload for the interview room (PRD v2.1 §7.5.3, §11).
 *
 * <p><strong>The recording never traverses our application servers.</strong> The
 * browser captures at 480p / 600 kbps with Opus audio, requests a pre-signed PUT
 * URL here, and uploads directly to object storage. A 30-minute interview is
 * roughly 135 MB; routing that through the app would dominate its resource
 * profile and destroy the economics that make ₹100 per interview profitable.
 *
 * <p>Cloudflare R2's zero egress fee is material on the read side — recording
 * playback is the only meaningful egress this product generates.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/candidate/recording/upload-url} — pre-signed PUT, max 500 MB</li>
 *   <li>{@code POST /api/v1/candidate/recording/upload-confirm} — ownership-validated</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/candidate/recording")
public class CandidateRecordingController {

    private static final Logger log = LoggerFactory.getLogger(CandidateRecordingController.class);

    /** Long enough to upload 135 MB on a poor connection, short enough to bound exposure. */
    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(30);

    private final InterviewSessionRepository sessionRepository;
    private final StorageService storageService;
    private final UploadKeyService uploadKeyService;
    private final StorageObjectRecorder storageObjectRecorder;

    public CandidateRecordingController(InterviewSessionRepository sessionRepository,
                                        StorageService storageService,
                                        UploadKeyService uploadKeyService,
                                        StorageObjectRecorder storageObjectRecorder) {
        this.sessionRepository     = sessionRepository;
        this.storageService        = storageService;
        this.uploadKeyService      = uploadKeyService;
        this.storageObjectRecorder = storageObjectRecorder;
    }

    /**
     * Issues a pre-signed PUT URL for this candidate's own recording.
     *
     * <p>The key is derived from the session's company and the session itself, so
     * a candidate cannot obtain a URL that writes into another tenant's namespace.
     */
    @GetMapping("/upload-url")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, String>> uploadUrl(
            @RequestParam(defaultValue = "video/webm") @NotBlank String contentType) {

        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = requireSession(sessionId);

        String objectKey = uploadKeyService.deriveKey(
                UploadKind.RECORDING, session.getCompanyId(), sessionId, contentType);
        String url = storageService.generatePresignedUploadUrl(objectKey, contentType, UPLOAD_URL_EXPIRY);

        return ApiResponse.ok(Map.of(
                "uploadUrl", url,
                "objectKey", objectKey,
                "contentType", contentType,
                "maxBytes", String.valueOf(UploadKind.RECORDING.getMaxBytes())));
    }

    /**
     * Confirms the upload and records the key against the session.
     *
     * <p>The key is validated against this session's tenant and the object is
     * inspected for size and MIME conformance before anything is persisted — a
     * pre-signed PUT cannot bound the body, so a client handed a URL could
     * otherwise upload arbitrary content of arbitrary size (§7.1.3).
     */
    @PostMapping("/upload-confirm")
    @Transactional
    public ApiResponse<Map<String, String>> confirmUpload(@RequestBody Map<String, String> body) {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = requireSession(sessionId);

        String ownedKey = uploadKeyService.validateOwnedKey(
                UploadKind.RECORDING, session.getCompanyId(), sessionId, body.get("objectKey"));
        StorageService.VerifiedObject verified =
                storageService.verifyUploadedObject(ownedKey, UploadKind.RECORDING);
        storageObjectRecorder.record(
                session.getCompanyId(), sessionId, StorageObjectType.RECORDING, ownedKey, verified);

        session.setRecordingS3Key(ownedKey);
        sessionRepository.save(session);

        log.info("Interview recording confirmed: sessionId={} key={}", sessionId, ownedKey);
        return ApiResponse.ok(Map.of("status", "confirmed"));
    }

    private InterviewSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }
}
