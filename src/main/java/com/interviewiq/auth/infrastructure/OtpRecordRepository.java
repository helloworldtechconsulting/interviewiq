package com.interviewiq.auth.infrastructure;

import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.auth.domain.OtpRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OtpRecordRepository extends JpaRepository<OtpRecord, UUID> {

    /**
     * Find the most recent valid (unused, unexpired) OTP for an email and purpose.
     * The OtpService validates the BCrypt hash against the candidate OTP codes.
     */
    List<OtpRecord> findByEmailAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, OtpPurpose purpose, OffsetDateTime now);

    /** Scheduled cleanup: delete expired or used OTPs (partial index supports this). */
    @Modifying
    @Query("DELETE FROM OtpRecord o WHERE o.expiresAt < :before OR o.used = true")
    int deleteExpiredAndUsed(OffsetDateTime before);
}
