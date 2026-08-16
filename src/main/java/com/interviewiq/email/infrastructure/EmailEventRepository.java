package com.interviewiq.email.infrastructure;

import com.interviewiq.email.domain.EmailEvent;
import com.interviewiq.email.domain.EmailStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailEventRepository extends JpaRepository<EmailEvent, UUID> {

    /** Correlate a provider delivery webhook back to the originating email record. */
    Optional<EmailEvent> findByProviderMessageId(String providerMessageId);

    Page<EmailEvent> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** Queued emails pending dispatch — for the email worker. */
    Page<EmailEvent> findAllByStatusOrderByCreatedAtAsc(EmailStatus status, Pageable pageable);

    /**
     * Most recent sends to an address, newest first — used to attribute an
     * inbound bounce to the email that caused it (INTIQ-32).
     *
     * <p>Matched on recipient rather than {@code provider_message_id} because
     * the SMTP providers behind {@code JavaMailSender} do not reliably echo the
     * {@code Message-ID} we generated; several rewrite it. The recipient is the
     * one field every provider agrees on.
     */
    List<EmailEvent> findTop10ByRecipientEmailAndStatusOrderByCreatedAtDesc(
            String recipientEmail, EmailStatus status);
}
