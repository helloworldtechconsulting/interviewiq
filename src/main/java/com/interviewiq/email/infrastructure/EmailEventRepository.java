package com.interviewiq.email.infrastructure;

import com.interviewiq.email.domain.EmailEvent;
import com.interviewiq.email.domain.EmailStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailEventRepository extends JpaRepository<EmailEvent, UUID> {

    /** Correlate a provider delivery webhook back to the originating email record. */
    Optional<EmailEvent> findByProviderMessageId(String providerMessageId);

    Page<EmailEvent> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** Queued emails pending dispatch — for the email worker. */
    Page<EmailEvent> findAllByStatusOrderByCreatedAtAsc(EmailStatus status, Pageable pageable);
}
