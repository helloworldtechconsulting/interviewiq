package com.interviewiq.email.infrastructure;

import com.interviewiq.email.domain.EmailSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

    /**
     * Pre-send suppression check. Called on every outbound email dispatch.
     * Backed by the UNIQUE index on email (V036) — O(log n).
     */
    boolean existsByEmail(String email);

    /** Load suppression record for a given email — used by SES webhook handler. */
    Optional<EmailSuppression> findByEmail(String email);
}
