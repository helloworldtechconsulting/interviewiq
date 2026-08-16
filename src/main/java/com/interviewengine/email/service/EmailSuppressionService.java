package com.interviewengine.email.service;

import com.interviewengine.email.domain.EmailSuppression;
import com.interviewengine.email.domain.SuppressionReason;
import com.interviewengine.email.infrastructure.EmailSuppressionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * The suppression list: addresses we have stopped sending to (INTIQ-32, §13.3).
 *
 * <h2>Why suppression matters more than it looks</h2>
 *
 * <p>Deliverability is scored per sending domain, not per recipient. Repeatedly
 * mailing an address that hard-bounces is one of the strongest spam signals
 * there is, and the cost lands on <em>every other</em> recipient: invites to
 * live candidates start going to junk. One dead address left unsuppressed
 * degrades the product for everyone else on the domain.
 *
 * <p>The flip side is that suppression is a silent block. An address on this
 * list receives nothing — no invite, no OTP, no report. That is why only hard
 * bounces and complaints put an address here, and why {@link #release} exists.
 */
@Service
public class EmailSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(EmailSuppressionService.class);

    private final EmailSuppressionRepository repository;

    public EmailSuppressionService(EmailSuppressionRepository repository) {
        this.repository = repository;
    }

    /** True when mail to this address must not be attempted. */
    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {
        return email != null && repository.existsByEmail(normalise(email));
    }

    @Transactional(readOnly = true)
    public Optional<EmailSuppression> find(String email) {
        return email == null ? Optional.empty() : repository.findByEmail(normalise(email));
    }

    /**
     * Adds an address to the suppression list, or leaves the existing entry
     * alone if it is already there.
     *
     * <p>Runs in its own transaction. Suppression is called from inside webhook
     * processing, and a suppression that rolled back with an unrelated failure
     * later in the handler would leave us still mailing an address the provider
     * has explicitly told us to stop mailing — with no second notification
     * coming, because the provider considers it delivered.
     *
     * <p>First reason wins. An address that hard-bounced and was later
     * complained about is already suppressed; overwriting the reason would lose
     * the earlier and more actionable fact.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suppress(String email, SuppressionReason reason, String providerNotificationId, String notes) {
        String normalised = normalise(email);
        if (normalised.isBlank()) {
            return;
        }
        if (repository.existsByEmail(normalised)) {
            log.debug("Address already suppressed, leaving as-is: {}", normalised);
            return;
        }

        EmailSuppression row = new EmailSuppression();
        row.setEmail(normalised);
        row.setReason(reason);
        row.setProviderNotificationId(blankToNull(providerNotificationId));
        row.setNotes(blankToNull(notes));

        try {
            repository.save(row);
            log.info("Address suppressed: email={} reason={}", normalised, reason);
        } catch (DataIntegrityViolationException e) {
            // Two webhook deliveries for the same address raced past the
            // existsByEmail check. The unique index did its job; the outcome we
            // wanted (address is suppressed) holds either way.
            log.debug("Concurrent suppression of {} — already present", normalised);
        }
    }

    /**
     * Removes an address from the suppression list.
     *
     * <p>Needed because suppression is occasionally wrong about a person rather
     * than an address: a mailbox that was full for a fortnight, a corporate
     * filter that reported a complaint nobody made, a typo'd address that has
     * since been corrected to the same string. Without a way out, that
     * candidate can never be invited again — a permanent consequence from a
     * signal we know to be imperfect.
     *
     * @return true if an entry was removed
     */
    @Transactional
    public boolean release(String email) {
        String normalised = normalise(email);
        return repository.findByEmail(normalised)
                .map(row -> {
                    repository.delete(row);
                    log.info("Address released from suppression: email={} was={}", normalised, row.getReason());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Lowercases and trims. The {@code email_suppressions} table carries a
     * {@code CHECK (email = lower(email))}, so anything else is a constraint
     * violation rather than a silent mismatch — but normalising here means the
     * lookup path and the write path agree, which is what actually prevents a
     * suppressed address from slipping through under different casing.
     */
    private String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
