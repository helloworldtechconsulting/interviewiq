package com.interviewiq.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight, dependency-free test data factory.
 *
 * <p>Returns plain DTOs / maps that can be used as request bodies without
 * coupling the test code to the production entity hierarchy. For tests that
 * need full domain objects (built via repositories), use the helpers added
 * to {@code AbstractIntegrationTest} subclasses.
 */
public final class TestDataFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDataFactory() {
        // utility
    }

    /** A unique slug like {@code acme-7c29}. */
    public static String uniqueSlug() {
        return "acme-" + Integer.toHexString(COUNTER.incrementAndGet())
                + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    /** A unique email address. */
    public static String uniqueEmail() {
        return "test-" + UUID.randomUUID().toString().substring(0, 8) + "@interviewiq.test";
    }

    /** A safe default password that satisfies the 8–128 char rule. */
    public static String defaultPassword() {
        return "Passw0rd!";
    }

    /**
     * Returns a {@code CompanyOnboardRequest}-shaped map (snake_case keys).
     */
    public static java.util.Map<String, Object> companyOnboardRequest(
            String companyName, String email) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("company_name", companyName);
        body.put("slug", uniqueSlug());
        body.put("admin_name", "Test Admin");
        body.put("email", email);
        body.put("password", defaultPassword());
        return body;
    }

    /**
     * Returns a {@code CreateJobRequest}-shaped map. Field names are best-effort
     * snake_case — actual schemas live with the controller DTOs.
     */
    public static java.util.Map<String, Object> createJobRequest(String title) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", title);
        body.put("description", "Test job description for " + title);
        body.put("employment_type", "FULL_TIME");
        body.put("location_type", "REMOTE");
        return body;
    }

    /**
     * Returns a {@code CreateCandidateRequest}-shaped map.
     */
    public static java.util.Map<String, Object> createCandidateRequest(UUID jobOpeningId) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("job_opening_id", jobOpeningId.toString());
        body.put("full_name", "Test Candidate");
        body.put("email", uniqueEmail());
        return body;
    }
}
