package com.interviewiq.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Candidate-driven scheduling configuration (PRD v2.1 §7.4).
 *
 * <p>Bound to the {@code app.scheduling} namespace.
 *
 * <p>Note what is <em>not</em> here: there are no business hours, no blackout
 * periods and no quiet-hours setting. Availability is genuinely 24×7 — the AI
 * does not keep office hours, and a candidate who wants to interview at 11pm on
 * a Sunday should be able to. Quiet hours are deliberately not built; the PRD is
 * explicit that if a customer later objects, the setting is a small addition and
 * should not be built speculatively.
 *
 * <p>{@code app.session.invite-buffer} is also deliberately absent. It was
 * deleted in v2.1: the 30-minute buffer was always a proxy for "have the
 * questions finished generating?", and measuring readiness directly is both
 * faster for the candidate and honest about what the constraint actually is
 * (§7.4.3).
 */
@ConfigurationProperties(prefix = "app.scheduling")
public class SchedulingProperties {

    /**
     * How many interviews may run concurrently within any one 5-minute bucket.
     *
     * <p>With employer windows gone, this single number is the only thing shaping
     * the calendar — the mechanism by which the calendar blocks itself. It comes
     * from the concurrency analysis: the MVP target is at least 25 simultaneous
     * interviews, load-tested to 50 (§8).
     *
     * <p>Raising it admits more bookings per slot; the constraint that should
     * bound it is pod capacity, so it is monitored alongside bucket saturation
     * rather than set once and forgotten.
     */
    private int bucketCapacity = 25;

    /**
     * How far ahead candidates may book.
     *
     * <p>The available-times query must return in under 500 ms p95 over a 30-day
     * horizon (§8), which is what this bounds.
     */
    private Duration bookingHorizon = Duration.ofDays(30);

    /**
     * Minimum notice before a bookable start time.
     *
     * <p>Small on purpose. This is not the deleted invite buffer returning by
     * another name — it exists only so that a candidate cannot book a slot that
     * begins while their own booking request is still in flight. "Start now"
     * bypasses it entirely.
     */
    private Duration minimumNotice = Duration.ofMinutes(5);

    /**
     * SLA on question generation, alarmed if breached (§7.4.3, §8).
     *
     * <p>Typical generation is ~20 seconds. This is a monitoring threshold, not a
     * product rule the candidate ever experiences — it exists so the readiness
     * gate has a bound.
     */
    private Duration questionGenerationSla = Duration.ofMinutes(30);

    /**
     * How late a candidate may arrive before the session is marked
     * {@code NO_SHOW} (§7.4.5).
     *
     * <p>Fifteen minutes is generous on purpose. The cost of waiting is a
     * capacity bucket held slightly longer; the cost of being too strict is a
     * candidate who took time off work, hit traffic, and finds their interview
     * gone — and a recruiter who loses a real applicant to a timer.
     */
    private Duration noShowGrace = Duration.ofMinutes(15);

    /**
     * How far ahead of the start time each reminder is sent.
     *
     * <p>Configurable rather than hardcoded because the right cadence is an
     * empirical question about completion rate, and completion rate is what the
     * product is billed on.
     */
    private Duration firstReminderLead  = Duration.ofHours(24);
    private Duration secondReminderLead = Duration.ofHours(1);

    /** Batch size for the reminder and no-show sweeps. */
    private int sweepBatchSize = 100;

    public Duration getNoShowGrace() { return noShowGrace; }
    public void setNoShowGrace(Duration noShowGrace) { this.noShowGrace = noShowGrace; }

    public Duration getFirstReminderLead() { return firstReminderLead; }
    public void setFirstReminderLead(Duration v) { this.firstReminderLead = v; }

    public Duration getSecondReminderLead() { return secondReminderLead; }
    public void setSecondReminderLead(Duration v) { this.secondReminderLead = v; }

    public int getSweepBatchSize() { return sweepBatchSize; }
    public void setSweepBatchSize(int sweepBatchSize) { this.sweepBatchSize = sweepBatchSize; }

    public int getBucketCapacity() { return bucketCapacity; }
    public void setBucketCapacity(int bucketCapacity) { this.bucketCapacity = bucketCapacity; }

    public Duration getBookingHorizon() { return bookingHorizon; }
    public void setBookingHorizon(Duration bookingHorizon) { this.bookingHorizon = bookingHorizon; }

    public Duration getMinimumNotice() { return minimumNotice; }
    public void setMinimumNotice(Duration minimumNotice) { this.minimumNotice = minimumNotice; }

    public Duration getQuestionGenerationSla() { return questionGenerationSla; }
    public void setQuestionGenerationSla(Duration questionGenerationSla) { this.questionGenerationSla = questionGenerationSla; }
}
