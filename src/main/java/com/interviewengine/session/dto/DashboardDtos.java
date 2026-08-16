package com.interviewengine.session.dto;

import java.util.List;

/**
 * Employer dashboard payloads (PRD v2.1 §7.7, INTIQ-73).
 *
 * <p>These exist because the dashboard was computing its own numbers from
 * {@code totalElements} on four unrelated list queries — asking for one row and
 * reading the count off the page envelope. That is cheap and wrong in a specific
 * way: the counts it produced answered "how many rows would this list return",
 * which is not the same question as "how many interviews are pending". A
 * completed session and a cancelled one both count as sessions.
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    /**
     * Headline counters.
     *
     * @param activeJobs        openings currently accepting candidates
     * @param totalCandidates   candidates on the books, all statuses
     * @param pendingInterviews invited, scheduled, running or being scored — the
     *                          number that tells a recruiter what is in flight
     * @param completedInterviews interviews with a finished report
     * @param reportsAwaitingReview completed interviews the recruiter has not opened
     * @param noShows           booked and not attended, last 30 days
     */
    public record DashboardStats(
            long activeJobs,
            long totalCandidates,
            long pendingInterviews,
            long completedInterviews,
            long reportsAwaitingReview,
            long noShows
    ) {}

    /**
     * One bar of the score histogram.
     *
     * @param bandLabel the score range, e.g. {@code "60-69"}
     * @param count     how many completed interviews fell in it
     */
    public record ScoreBand(String bandLabel, long count) {}

    /**
     * Score distribution across completed interviews.
     *
     * <p>Carries the sample size alongside the bands on purpose. A histogram of
     * four interviews looks identical in shape to one of four hundred, and a
     * recruiter drawing conclusions about "our candidate quality" from three data
     * points is the failure mode this number prevents.
     */
    public record ScoreDistribution(long totalScored, List<ScoreBand> bands) {}
}
