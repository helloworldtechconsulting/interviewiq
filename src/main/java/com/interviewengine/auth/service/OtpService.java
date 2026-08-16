package com.interviewengine.auth.service;

import com.interviewengine.auth.domain.OtpPurpose;
import com.interviewengine.auth.domain.OtpRecord;
import com.interviewengine.auth.infrastructure.OtpRecordRepository;
import com.interviewengine.email.service.EmailService;
import com.interviewengine.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Issues, validates, and expires one-time passwords.
 *
 * <p>OTPs are 6-digit codes BCrypt-hashed before storage.
 * The raw code is emailed to the recipient and never persisted.
 *
 * <p>Lookup uses the same BCrypt match loop used for password verification:
 * the latest N unexpired records are retrieved and the raw code is checked
 * against each hash until a match is found or all candidates are exhausted.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    /** OTP valid window — 10 minutes. */
    private static final long OTP_EXPIRY_MINUTES = 10;

    /** How many candidates to check during verification (prevents BCrypt DoS). */
    private static final int MAX_CANDIDATES = 3;

    private final OtpRecordRepository otpRecordRepository;
    private final EmailService        emailService;
    private final PasswordEncoder     passwordEncoder;
    private final SecureRandom        secureRandom = new SecureRandom();

    public OtpService(OtpRecordRepository otpRecordRepository,
                      EmailService emailService,
                      PasswordEncoder passwordEncoder) {
        this.otpRecordRepository = otpRecordRepository;
        this.emailService        = emailService;
        this.passwordEncoder     = passwordEncoder;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates a 6-digit OTP, persists the BCrypt hash, and emails the code.
     *
     * @param to        recipient email (normalised to lowercase before persisting)
     * @param purpose   determines email template and DB purpose value
     * @param companyId nullable — null for EMAIL_VERIFICATION (not yet created)
     * @param userId    nullable — null for EMAIL_VERIFICATION / PASSWORD_RESET
     */
    @Transactional
    public void sendOtp(String to, OtpPurpose purpose, UUID companyId, UUID userId) {
        String email = to.toLowerCase();
        String raw   = generateRaw();

        OtpRecord record = new OtpRecord();
        record.setEmail(email);
        record.setPurpose(purpose);
        record.setOtpHash(passwordEncoder.encode(raw));
        record.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(OTP_EXPIRY_MINUTES));
        otpRecordRepository.save(record);

        emailService.sendOtpEmail(email, purpose, raw, companyId, userId);
        log.debug("OTP issued: email={} purpose={}", email, purpose);
    }

    /**
     * Validates a raw OTP submitted by the user.
     *
     * <p>On success the matching record is marked used so it cannot be replayed.
     *
     * @param email   the recipient's email (normalised to lowercase)
     * @param rawOtp  the 6-digit code submitted by the user
     * @param purpose must match the purpose the OTP was issued for
     * @throws ValidationException if no valid, unused, unexpired OTP matches
     */
    @Transactional
    public void verifyOtp(String email, String rawOtp, OtpPurpose purpose) {
        String emailLower = email.toLowerCase();
        List<OtpRecord> candidates = otpRecordRepository
                .findByEmailAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        emailLower, purpose, OffsetDateTime.now(ZoneOffset.UTC));

        // Check at most MAX_CANDIDATES to bound BCrypt compute time
        for (OtpRecord candidate : candidates.stream().limit(MAX_CANDIDATES).toList()) {
            if (passwordEncoder.matches(rawOtp, candidate.getOtpHash())) {
                candidate.setUsed(true);
                otpRecordRepository.save(candidate);
                log.debug("OTP verified: email={} purpose={}", emailLower, purpose);
                return;
            }
        }

        throw new ValidationException("Invalid or expired verification code.");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String generateRaw() {
        int code = 100_000 + secureRandom.nextInt(900_000);
        return String.valueOf(code);
    }
}
