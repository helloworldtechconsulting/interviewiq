package com.interviewiq.auth.infrastructure;

import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Primary login query. MUST always scope to companyId — the same email
     * can exist in multiple companies. Also filters by emailVerified to
     * block unverified accounts at the query level.
     */
    Optional<User> findByCompanyIdAndEmailAndEmailVerifiedTrue(UUID companyId, String email);

    /** Used by AuthService for full profile lookup after JWT validation. */
    Optional<User> findByCompanyIdAndId(UUID companyId, UUID id);

    /** Google OAuth: resolve user by OAuth sub claim within a company. */
    Optional<User> findByCompanyIdAndGoogleSubject(UUID companyId, String googleSubject);

    /** Global Google OAuth lookup when company context is not yet established. */
    Optional<User> findByGoogleSubject(String googleSubject);

    /** Team management: list all users in a company. */
    List<User> findAllByCompanyIdOrderByFullNameAsc(UUID companyId);

    boolean existsByCompanyIdAndEmail(UUID companyId, String email);

    /**
     * Lookup for email-verification and password-reset flows where the account
     * may not yet be verified. Unlike {@code findByCompanyIdAndEmailAndEmailVerifiedTrue},
     * this does NOT filter on {@code emailVerified}.
     */
    Optional<User> findByCompanyIdAndEmail(UUID companyId, String email);

    /**
     * The company's founding admin — earliest-created user with the ADMIN role.
     *
     * <p>Used as the recipient for account-level notices like the low-balance
     * warning. Deliberately the <em>first</em> admin rather than an arbitrary
     * one: on a self-serve signup that is the person who created the account and
     * holds the payment method, so they are the one who can act on it.
     */
    Optional<User> findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(UUID companyId, UserRole role);
}
