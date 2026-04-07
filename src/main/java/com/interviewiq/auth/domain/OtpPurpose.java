package com.interviewiq.auth.domain;

/**
 * DB CHECK values: 'LOGIN', 'PASSWORD_RESET', 'EMAIL_VERIFICATION', 'CANDIDATE_AUTH'
 *
 * <p>CANDIDATE_AUTH: candidates authenticate via email OTP but have no
 * {@code users} table row — the OTP record is keyed only by email.
 */
public enum OtpPurpose {
    LOGIN,
    PASSWORD_RESET,
    EMAIL_VERIFICATION,
    CANDIDATE_AUTH
}
