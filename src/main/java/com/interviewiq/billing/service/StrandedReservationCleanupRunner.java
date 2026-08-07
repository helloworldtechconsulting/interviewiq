package com.interviewiq.billing.service;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.session.domain.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One-off remediation for reservations stranded by the pre-fix {@code SessionExpiryJob},
 * which bulk-flipped INVITED → EXPIRED without ever releasing the wallet reservation each
 * session held. Those PENDING RESERVATION rows still ring-fence {@code reservedPaise} on
 * their wallet even though the session is terminal.
 *
 * <p>This runner scans PENDING RESERVATION transactions whose session is now EXPIRED and
 * releases each via {@link WalletService#releaseFunds}, so it reuses the exact same
 * reserve/settle/release ledger logic (proper RELEASE rows, {@code @Version} respected,
 * wallet-integrity trigger honoured) rather than mutating balances by raw SQL.
 *
 * <p><b>Disabled by default.</b> Guarded by {@code app.billing.strand-cleanup.enabled}
 * (default {@code false}) so ops enables it for exactly one deploy, confirms the drained
 * total in the logs, then turns it back off. The work is idempotent regardless — once a
 * reservation is RELEASED it no longer matches the PENDING scan, so an accidental re-run
 * is a harmless no-op. Only EXPIRED sessions are touched: INVITED (still live) and STARTED
 * (in progress) reservations are deliberately left alone.
 */
@Component
public class StrandedReservationCleanupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StrandedReservationCleanupRunner.class);

    private static final int PAGE_SIZE = 200;

    private final WalletTransactionRepository txRepository;
    private final WalletService walletService;
    private final boolean enabled;

    public StrandedReservationCleanupRunner(
            WalletTransactionRepository txRepository,
            WalletService walletService,
            @Value("${app.billing.strand-cleanup.enabled:false}") boolean enabled) {
        this.txRepository = txRepository;
        this.walletService = walletService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("StrandedReservationCleanupRunner: disabled (app.billing.strand-cleanup.enabled=false)");
            return;
        }

        log.info("StrandedReservationCleanupRunner: starting stranded-reservation sweep");
        int released = 0;
        int failed = 0;
        long releasedPaise = 0L;

        // Every processed tx id, so a row that does not leave the PENDING set — because it
        // threw, or because releaseFunds could not locate it (idempotent no-op on a data
        // anomaly) — is not mistaken for progress on the next page-0 re-fetch. A round with
        // zero previously-unseen rows means the set has stopped shrinking: stop.
        Set<UUID> processed = new HashSet<>();

        while (true) {
            // Every returned row is a PENDING reservation for an EXPIRED session, i.e.
            // genuinely releasable. Always re-fetch page 0: each release leaves the
            // PENDING set, so the result shrinks to empty.
            List<WalletTransaction> batch = txRepository.findReservationsForSessionsInStatus(
                    TransactionType.RESERVATION, TransactionStatus.PENDING, SessionStatus.EXPIRED,
                    PageRequest.of(0, PAGE_SIZE)).getContent();

            if (batch.isEmpty()) {
                break;
            }

            int newThisRound = 0;
            for (WalletTransaction reservation : batch) {
                if (!processed.add(reservation.getId())) {
                    continue;   // already attempted in a prior round — skip to detect a stall
                }
                newThisRound++;
                UUID sessionId = reservation.getSessionId();
                try {
                    walletService.releaseFunds(reservation.getCompanyId(), sessionId);
                    released++;
                    releasedPaise += reservation.getAmountPaise();
                } catch (Exception e) {
                    failed++;
                    log.error("StrandedReservationCleanupRunner: failed to release reservation "
                            + "txId={} sessionId={}: {}", reservation.getId(), sessionId, e.getMessage(), e);
                }
            }

            // No previously-unseen rows this round => the PENDING set is no longer shrinking
            // (rows stuck due to failures or unresolvable lookups). Stop instead of looping.
            if (newThisRound == 0) {
                log.error("StrandedReservationCleanupRunner: {} reservation(s) remain PENDING but "
                        + "could not be released; aborting sweep for manual follow-up", batch.size());
                break;
            }
        }

        log.info("StrandedReservationCleanupRunner: sweep complete — released={} reservation(s) "
                + "totalPaise={} ({}), failed={}",
                released, releasedPaise, formatRupees(releasedPaise), failed);
    }

    private static String formatRupees(long paise) {
        return "₹" + (paise / 100) + "." + String.format("%02d", Math.abs(paise % 100));
    }
}
