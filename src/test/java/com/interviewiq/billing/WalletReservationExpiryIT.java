package com.interviewiq.billing;

import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.billing.service.StrandedReservationCleanupRunner;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.session.scheduler.SessionExpiryJob;
import com.interviewiq.session.service.SessionExpiryService;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.support.AbstractPostgresIntegrationTest;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration tests for the wallet-reservation release path on invite expiry,
 * running against a real Flyway-migrated PostgreSQL (constraints + wallet-integrity
 * trigger active).
 *
 * <p>Covers the ticket's acceptance criteria:
 * <ul>
 *   <li>{@link SessionExpiryJob} expires an INVITED session and drives {@code reservedPaise} to zero.</li>
 *   <li>{@link StrandedReservationCleanupRunner} drains a reservation left stranded by the old bug.</li>
 * </ul>
 *
 * <p>Test methods run with {@code NOT_SUPPORTED} so there is no ambient test transaction:
 * the SQL seed commits immediately and the services' own transactions (including
 * {@code REQUIRES_NEW}) observe it, exactly as at runtime.
 */
@Import({
        WalletService.class,
        SessionExpiryService.class,
        SessionExpiryJob.class,
        WalletReservationExpiryIT.MockBillingBeans.class
})
class WalletReservationExpiryIT extends AbstractPostgresIntegrationTest {

    private static final long COST_PAISE = 5000L;

    @TestConfiguration
    static class MockBillingBeans {
        // WalletService needs these to construct, but the reserve/release paths never touch them.
        @Bean RazorpayClient razorpayClient() { return mock(RazorpayClient.class); }
        @Bean RazorpayProperties razorpayProperties() { return mock(RazorpayProperties.class); }
    }

    @Autowired SessionExpiryJob sessionExpiryJob;
    @Autowired WalletService walletService;
    @Autowired WalletTransactionRepository txRepository;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // Clean slate — the SessionExpiryJob query is global, so leftover rows would leak across tests.
        jdbc.execute("TRUNCATE wallet_transactions, interview_sessions, wallets, "
                + "candidates, job_openings, users, companies CASCADE");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiryJob_releasesReservation_reservedPaiseReturnsToZero() {
        Seed seed = seedInvitedSessionWithReservation();

        sessionExpiryJob.expireStaleInvites();

        assertThat(reservedPaise(seed.walletId)).isZero();
        assertThat(balancePaise(seed.walletId)).isEqualTo(COST_PAISE);   // balance untouched by a release
        assertThat(sessionStatus(seed.sessionId)).isEqualTo("EXPIRED");
        assertThat(reservationStatus(seed.sessionId)).isEqualTo("RELEASED");
        assertThat(hasReleaseTransaction(seed.sessionId)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cleanupRunner_drainsAlreadyStrandedReservation() {
        // Simulate the pre-fix bug: session already EXPIRED but reservation still PENDING
        // and funds still ring-fenced.
        Seed seed = seedInvitedSessionWithReservation();
        jdbc.update("UPDATE interview_sessions SET status = 'EXPIRED' WHERE id = ?", seed.sessionId);

        StrandedReservationCleanupRunner runner =
                new StrandedReservationCleanupRunner(txRepository, walletService, /* enabled */ true);
        runner.run(null);

        assertThat(reservedPaise(seed.walletId)).isZero();
        assertThat(reservationStatus(seed.sessionId)).isEqualTo("RELEASED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cleanupRunner_leavesLiveInvitedReservationUntouched() {
        // A still-INVITED session's reservation must NOT be drained by the cleanup sweep.
        Seed seed = seedInvitedSessionWithReservation();

        StrandedReservationCleanupRunner runner =
                new StrandedReservationCleanupRunner(txRepository, walletService, true);
        runner.run(null);

        assertThat(reservedPaise(seed.walletId)).isEqualTo(COST_PAISE);
        assertThat(reservationStatus(seed.sessionId)).isEqualTo("PENDING");
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private record Seed(UUID companyId, UUID walletId, UUID sessionId) {}

    private Seed seedInvitedSessionWithReservation() {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationTxId = UUID.randomUUID();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime createdAt = now.minusDays(2);
        OffsetDateTime inviteExpiresAt = now.minusDays(1);   // elapsed, and > created_at (V007 CHECK)

        jdbc.update("INSERT INTO companies (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                companyId, "Acme " + companyId, "acme-" + companyId);

        jdbc.update("INSERT INTO users (id, company_id, full_name, email, password_hash, role) "
                        + "VALUES (?, ?, ?, ?, ?, 'ADMIN')",
                userId, companyId, "Hiring Manager", "hm-" + userId + "@example.com", "x");

        jdbc.update("INSERT INTO job_openings (id, company_id, created_by, title, jd_extraction_status, status) "
                        + "VALUES (?, ?, ?, ?, 'DONE', 'ACTIVE')",
                jobId, companyId, userId, "Backend Engineer");

        jdbc.update("INSERT INTO candidates (id, company_id, job_opening_id, email, full_name) "
                        + "VALUES (?, ?, ?, ?, ?)",
                candidateId, companyId, jobId, "cand-" + candidateId + "@example.com", "Jane Candidate");

        jdbc.update("INSERT INTO wallets (id, company_id, balance_paise, reserved_paise, version) "
                        + "VALUES (?, ?, ?, ?, 0)",
                walletId, companyId, COST_PAISE, COST_PAISE);

        jdbc.update("INSERT INTO interview_sessions "
                        + "(id, company_id, job_opening_id, candidate_id, invite_token_hash, "
                        + " invite_expires_at, status, question_generation_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'INVITED', 'PENDING', ?, ?)",
                sessionId, companyId, jobId, candidateId, "hash-" + sessionId,
                inviteExpiresAt, createdAt, createdAt);

        jdbc.update("INSERT INTO wallet_transactions "
                        + "(id, company_id, wallet_id, session_id, transaction_type, amount_paise, "
                        + " balance_after_paise, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVATION', ?, ?, 'PENDING', ?)",
                reservationTxId, companyId, walletId, sessionId, COST_PAISE, COST_PAISE, createdAt);

        return new Seed(companyId, walletId, sessionId);
    }

    // ── Assertions via fresh reads (bypass any JPA first-level cache) ─────────

    private long reservedPaise(UUID walletId) {
        return jdbc.queryForObject("SELECT reserved_paise FROM wallets WHERE id = ?", Long.class, walletId);
    }

    private long balancePaise(UUID walletId) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, walletId);
    }

    private String sessionStatus(UUID sessionId) {
        return jdbc.queryForObject("SELECT status FROM interview_sessions WHERE id = ?", String.class, sessionId);
    }

    private String reservationStatus(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT status FROM wallet_transactions "
                        + "WHERE session_id = ? AND transaction_type = 'RESERVATION'",
                String.class, sessionId);
    }

    private boolean hasReleaseTransaction(UUID sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM wallet_transactions "
                        + "WHERE session_id = ? AND transaction_type = 'RELEASE'",
                Integer.class, sessionId);
        return count != null && count > 0;
    }
}
