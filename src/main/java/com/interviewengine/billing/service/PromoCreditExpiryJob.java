package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.shared.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Sweeps promotional credit that has expired unspent (PRD v2.1 §7.8.3).
 *
 * <p>"Expired promotional credit is swept by a scheduled job and written back as
 * a reversing transaction so the balance and the ledger stay consistent."
 *
 * <p>The reversing entry matters more than it might appear. Silently reducing the
 * balance would leave a customer looking at a number that dropped for no visible
 * reason, with nothing in their transaction history to explain it — and would
 * make the wallet balance and the sum of its transactions disagree, which is the
 * kind of discrepancy that is discovered at the worst possible moment.
 *
 * <p>Named in §7.9's list of workers that must claim rather than poll: reversing
 * the same grant on two pods would take a company's promotional balance below
 * what it was granted.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class PromoCreditExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PromoCreditExpiryJob.class);

    private final WalletTransactionRepository txRepository;
    private final WalletService walletService;
    private final WorkerProperties workerProperties;

    public PromoCreditExpiryJob(WalletTransactionRepository txRepository,
                                WalletService walletService,
                                WorkerProperties workerProperties) {
        this.txRepository     = txRepository;
        this.walletService    = walletService;
        this.workerProperties = workerProperties;
    }

    /**
     * Runs daily at 04:00 UTC — staggered away from the 02:00 auth cleanup and
     * the 03:00 session expiry so the three do not contend on the database at
     * once.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void expireLapsedGrants() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int reversed = 0;

        while (true) {
            List<WalletTransaction> claimed = claimBatch(now);
            if (claimed.isEmpty()) {
                break;
            }

            for (WalletTransaction grant : claimed) {
                try {
                    walletService.expirePromotionalCredit(
                            grant.getCompanyId(), grant.getAmountPaise(), grant.getId());
                    txRepository.markGrantExpired(grant.getId());
                    reversed++;
                } catch (Exception e) {
                    // One company's failure must not strand every later grant in
                    // the batch; the next run reclaims this one.
                    log.error("PromoCreditExpiryJob: failed to expire grantId={} companyId={}: {}",
                            grant.getId(), grant.getCompanyId(), e.getMessage(), e);
                }
            }

            if (claimed.size() < workerProperties.getPromoExpiryBatchSize()) {
                break;
            }
        }

        if (reversed > 0) {
            log.info("PromoCreditExpiryJob: reversed {} expired promotional grant(s)", reversed);
        }
    }

    @Transactional
    protected List<WalletTransaction> claimBatch(OffsetDateTime now) {
        return txRepository.claimExpiredPromotionalGrants(
                now, workerProperties.getPromoExpiryBatchSize());
    }
}
