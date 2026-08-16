package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.TransactionStatus;
import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.session.domain.SessionStatus;
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

        Set<UUID> processed = new HashSet<>();

        while (true) {
            List<WalletTransaction> batch = txRepository.findReservationsForSessionsInStatus(
                    TransactionType.RESERVATION, TransactionStatus.PENDING, SessionStatus.EXPIRED,
                    PageRequest.of(0, PAGE_SIZE)).getContent();

            if (batch.isEmpty()) {
                break;
            }

            int newThisRound = 0;
            for (WalletTransaction reservation : batch) {
                if (!processed.add(reservation.getId())) {
                    continue;
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
