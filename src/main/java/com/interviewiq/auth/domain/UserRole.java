package com.interviewiq.auth.domain;

/**
 * User roles.
 *
 * <p>DB CHECK values: {@code 'ADMIN'}, {@code 'RECRUITER'}, {@code 'VIEWER'},
 * {@code 'PLATFORM_STAFF'} (see V053).
 */
public enum UserRole {

    /** A customer company's own administrator. Employer-facing. */
    ADMIN,

    /** Runs openings and candidates within one company. Employer-facing. */
    RECRUITER,

    /** Read-only access within one company. Employer-facing. */
    VIEWER,

    /**
     * InterviewIQ internal operations — the staff console (PRD v2.1 §7.8.3).
     *
     * <p>Distinct from {@link #ADMIN} on purpose. ADMIN is a <em>customer's</em>
     * administrator; reusing it for the internal console would hand every
     * customer's admin the ability to mint promotional credit for themselves,
     * which is exactly what "no employer-facing path can create a PROMO_CREDIT
     * transaction" (§7.1.3) forbids.
     *
     * <p>No self-service registration path assigns this role.
     */
    PLATFORM_STAFF;

    /** Whether this role may reach the internal staff console. */
    public boolean isPlatformStaff() {
        return this == PLATFORM_STAFF;
    }
}
