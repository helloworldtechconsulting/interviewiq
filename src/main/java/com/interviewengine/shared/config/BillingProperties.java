package com.interviewengine.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Billing configuration (PRD v2.1 §7.8).
 *
 * <p>Bound to the {@code app.billing} namespace.
 */
@ConfigurationProperties(prefix = "app.billing")
public class BillingProperties {

    /**
     * Cost of one completed interview, at <em>every</em> duration tier (§7.8.1).
     *
     * <p>Flat across tiers on purpose: the marginal cost of a longer interview is
     * LLM tokens measured in paise, and per-minute pricing would push employers
     * toward the wrong tier for the role.
     */
    private long sessionCostPaise = 10_000L;   // ₹100

    /**
     * Minimum wallet top-up (§7.8.1). Lowered from ₹500 in v2.1 — one interview.
     *
     * <p>This removes the "commit ₹500 before you've seen it work" barrier to
     * early adoption, and the PRD is emphatic that it should not be reversed:
     * at roughly 2% plus GST a ₹100 top-up costs about ₹2.36 to collect against
     * ₹11.80 on ₹500, and if small top-ups come to dominate the answer is to
     * <em>nudge toward larger ones with a bonus</em>, not to raise the floor. The
     * low floor is the adoption mechanism.
     */
    private long minimumTopUpPaise = 10_000L;  // ₹100

    /** Top-up presets offered in the UI (§7.8.1). */
    private List<Long> topUpPresetsPaise = new ArrayList<>(List.of(
            10_000L,     // ₹100
            50_000L,     // ₹500
            100_000L,    // ₹1,000
            250_000L,    // ₹2,500
            500_000L,    // ₹5,000
            1_000_000L   // ₹10,000
    ));

    /** GST rate on paid top-ups, shown separately on invoices (§7.8.1). */
    private int gstPercent = 18;

    /**
     * Balance at or below which the low-balance banner and alert email fire
     * (§7.7, §7.8.2). Counts paid and promotional balance together.
     */
    private long lowBalanceThresholdPaise = 30_000L;   // ₹300

    private final SignupGrant signupGrant = new SignupGrant();

    public SignupGrant getSignupGrant() { return signupGrant; }

    /**
     * The self-serve promotional grant applied on email verification (§7.8.3).
     *
     * <p>"The single highest-leverage item in the whole change set for hitting 20
     * paying clients in 60 days. Nobody buys an AI interviewer they have not
     * watched run."
     */
    public static class SignupGrant {

        private boolean enabled = true;

        /** Roughly three free interviews. */
        private long amountPaise = 30_000L;   // ₹300

        /** How long the grant lasts. Null means no expiry. */
        private Duration validFor = Duration.ofDays(60);

        /**
         * Treat public free-mail domains more strictly than corporate ones.
         *
         * <p>One of the two abuse guards in §7.8.3. A corporate domain
         * identifies an organisation; gmail.com identifies nothing, so a grant
         * per verified gmail address would be a grant per email address anyone
         * can mint.
         */
        private List<String> publicEmailDomains = new ArrayList<>(List.of(
                "gmail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com",
                "live.com", "icloud.com", "proton.me", "protonmail.com", "rediffmail.com",
                "aol.com", "gmx.com", "mail.com", "yandex.com", "zoho.com"));

        /**
         * Cap on total outstanding promotional credit across all companies.
         *
         * <p>"Grants are capped and monitored; the internal dashboard tracks
         * total promotional exposure." Zero disables the cap.
         */
        private long totalExposureCapPaise = 5_000_000L;   // ₹50,000

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getAmountPaise() { return amountPaise; }
        public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }

        public Duration getValidFor() { return validFor; }
        public void setValidFor(Duration validFor) { this.validFor = validFor; }

        public List<String> getPublicEmailDomains() { return publicEmailDomains; }
        public void setPublicEmailDomains(List<String> v) { this.publicEmailDomains = v; }

        public long getTotalExposureCapPaise() { return totalExposureCapPaise; }
        public void setTotalExposureCapPaise(long v) { this.totalExposureCapPaise = v; }
    }

    public long getSessionCostPaise() { return sessionCostPaise; }
    public void setSessionCostPaise(long sessionCostPaise) { this.sessionCostPaise = sessionCostPaise; }

    public long getMinimumTopUpPaise() { return minimumTopUpPaise; }
    public void setMinimumTopUpPaise(long minimumTopUpPaise) { this.minimumTopUpPaise = minimumTopUpPaise; }

    public List<Long> getTopUpPresetsPaise() { return topUpPresetsPaise; }
    public void setTopUpPresetsPaise(List<Long> topUpPresetsPaise) { this.topUpPresetsPaise = topUpPresetsPaise; }

    public int getGstPercent() { return gstPercent; }
    public void setGstPercent(int gstPercent) { this.gstPercent = gstPercent; }

    public long getLowBalanceThresholdPaise() { return lowBalanceThresholdPaise; }
    public void setLowBalanceThresholdPaise(long v) { this.lowBalanceThresholdPaise = v; }

    /** GST payable on a paid top-up, in paise. Promotional credit never bears GST. */
    public long gstOn(long amountPaise) {
        return Math.round(amountPaise * (gstPercent / 100.0));
    }
}
