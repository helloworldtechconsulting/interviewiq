package com.interviewiq.billing.service;

import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.dto.AdminDtos.AdminCompanyRow;
import com.interviewiq.billing.dto.AdminDtos.PlatformStats;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.billing.infrastructure.WalletTransactionRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform-staff view across all tenants (PRD v2.1 §7.7, INTIQ-35).
 *
 * <p>Every other service in this codebase is scoped to one company by
 * construction — {@code SecurityContext.requireCompanyId()} appears in nearly
 * every query. This one deliberately is not, which is why it sits behind
 * {@code PLATFORM_STAFF} and why that is enforced at the controller rather than
 * here: a service that quietly crosses tenants is one refactor away from being
 * called from somewhere that should not.
 *
 * <h2>The company list is assembled, not joined</h2>
 *
 * <p>Companies, wallets and session counts live in three places. The obvious
 * implementation is one query with two joins and a group-by; this instead pages
 * the companies and then batch-fetches the wallets and counts for that page.
 *
 * <p>Reason: the join produces a row per company per session and aggregates it
 * back down, which is fine at 20 companies and quietly quadratic in the number
 * of interviews. Paging first bounds the work to the page size regardless of how
 * much history the platform accumulates. It costs three queries instead of one,
 * and none of them grow.
 */
@Service
public class AdminConsoleService {

    private final CompanyRepository companyRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final InterviewSessionRepository sessionRepository;

    public AdminConsoleService(CompanyRepository companyRepository,
                               WalletRepository walletRepository,
                               WalletTransactionRepository txRepository,
                               InterviewSessionRepository sessionRepository) {
        this.companyRepository = companyRepository;
        this.walletRepository  = walletRepository;
        this.txRepository      = txRepository;
        this.sessionRepository = sessionRepository;
    }

    /** One page of companies with their interview counts and balances. */
    @Transactional(readOnly = true)
    public Page<AdminCompanyRow> listCompanies(Pageable pageable) {
        Page<Company> companies = companyRepository.findAll(pageable);
        List<UUID> ids = companies.getContent().stream().map(Company::getId).toList();

        if (ids.isEmpty()) {
            return companies.map(c -> row(c, null, 0, 0, 0));
        }

        Map<UUID, Wallet> wallets = walletRepository.findAllByCompanyIdIn(ids).stream()
                .collect(Collectors.toMap(Wallet::getCompanyId, Function.identity(), (a, b) -> a));

        Map<UUID, Long> completed = countsByCompany(
                sessionRepository.countCompletedByCompanyIdIn(ids));
        Map<UUID, Long> pending = countsByCompany(
                sessionRepository.countByCompanyIdInAndStatusIn(ids, pendingStatuses()));
        Map<UUID, Long> spend = countsByCompany(
                txRepository.sumByCompanyIdInAndType(ids, TransactionType.SETTLEMENT));

        return companies.map(c -> row(
                c,
                wallets.get(c.getId()),
                completed.getOrDefault(c.getId(), 0L),
                pending.getOrDefault(c.getId(), 0L),
                spend.getOrDefault(c.getId(), 0L)));
    }

    /** Platform-wide totals for the console header. */
    @Transactional(readOnly = true)
    public PlatformStats platformStats() {
        return new PlatformStats(
                companyRepository.countByStatus(CompanyStatus.ACTIVE),
                sessionRepository.countByStatus(SessionStatus.COMPLETED),
                sessionRepository.countByStatusIn(pendingStatuses()),
                txRepository.sumByType(TransactionType.SETTLEMENT),
                // Outstanding promotional credit is a liability, and the signup
                // grant is capped against exactly this figure (§7.8.3).
                walletRepository.sumPromoBalance(),
                walletRepository.sumReserved());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private AdminCompanyRow row(Company company, Wallet wallet,
                                long completed, long pending, long lifetimeSpend) {
        return new AdminCompanyRow(
                company.getId(),
                company.getName(),
                company.getSlug(),
                company.getStatus(),
                company.getCreatedAt(),
                completed,
                pending,
                wallet == null ? 0 : wallet.getBalancePaise(),
                wallet == null ? 0 : wallet.getPromoBalancePaise(),
                wallet == null ? 0 : wallet.getReservedPaise(),
                lifetimeSpend);
    }

    /** Turns {@code [companyId, count]} projections into a lookup. */
    private static Map<UUID, Long> countsByCompany(List<Object[]> rows) {
        Map<UUID, Long> out = new java.util.HashMap<>();
        for (Object[] row : rows) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * Derived from {@link SessionStatus#isPending()} rather than listed, so a new
     * non-terminal state cannot be added to the enum and silently omitted from
     * the platform's own view of what is in flight.
     */
    private static List<SessionStatus> pendingStatuses() {
        List<SessionStatus> pending = new ArrayList<>(4);
        for (SessionStatus status : SessionStatus.values()) {
            if (status.isPending()) {
                pending.add(status);
            }
        }
        return pending;
    }
}
