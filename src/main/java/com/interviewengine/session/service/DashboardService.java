package com.interviewengine.session.service;

import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.job.domain.JobStatus;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.session.dto.DashboardDtos.DashboardStats;
import com.interviewengine.session.dto.DashboardDtos.ScoreBand;
import com.interviewengine.session.dto.DashboardDtos.ScoreDistribution;
import com.interviewengine.session.dto.SessionResponse;
import com.interviewengine.session.infrastructure.EvaluationReportRepository;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.shared.security.SecurityContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard counters and analytics (PRD v2.1 §7.7, INTIQ-73).
 *
 * <h2>What was wrong before</h2>
 *
 * <p>The dashboard fetched four lists with {@code size=1} and read
 * {@code totalElements} off each page envelope. It looked efficient and produced
 * the wrong numbers, because a list count answers "how many rows match this
 * query", not "how many interviews need my attention". "Interviews" counted
 * cancelled and expired ones; "candidates" counted people whose interview
 * finished months ago. A recruiter looking at the number to decide whether to
 * chase anyone was reading a figure that could not answer that.
 *
 * <p>These queries count what the labels claim. Each is a {@code COUNT} in the
 * database rather than a page of rows thrown away.
 */
@Service
public class DashboardService {

    /** Recent activity shown on the dashboard — a glance, not a list page. */
    private static final int RECENT_SESSION_LIMIT = 5;

    /** No-shows are counted over a rolling window; the all-time figure is not actionable. */
    private static final int NO_SHOW_WINDOW_DAYS = 30;

    private final InterviewSessionRepository sessionRepository;
    private final EvaluationReportRepository reportRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final CandidateRepository candidateRepository;

    public DashboardService(InterviewSessionRepository sessionRepository,
                            EvaluationReportRepository reportRepository,
                            JobOpeningRepository jobOpeningRepository,
                            CandidateRepository candidateRepository) {
        this.sessionRepository    = sessionRepository;
        this.reportRepository     = reportRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.candidateRepository  = candidateRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        UUID companyId = SecurityContext.requireCompanyId();
        OffsetDateTime windowStart = OffsetDateTime.now(ZoneOffset.UTC).minusDays(NO_SHOW_WINDOW_DAYS);

        return new DashboardStats(
                jobOpeningRepository.countByCompanyIdAndStatus(companyId, JobStatus.ACTIVE),
                candidateRepository.countByCompanyId(companyId),
                // The pending set comes from SessionStatus.isPending() rather than
                // being spelled out here, so a new non-terminal state cannot be
                // added to the enum and silently omitted from the dashboard.
                sessionRepository.countByCompanyIdAndStatusIn(companyId, pendingStatuses()),
                sessionRepository.countByCompanyIdAndStatus(companyId, SessionStatus.COMPLETED),
                reportRepository.countUnreviewed(companyId),
                sessionRepository.countByCompanyIdAndStatusAndCreatedAtAfter(
                        companyId, SessionStatus.NO_SHOW, windowStart));
    }

    /** The five most recent sessions, for the activity strip. */
    @Transactional(readOnly = true)
    public List<SessionResponse> recentSessions() {
        UUID companyId = SecurityContext.requireCompanyId();
        return sessionRepository
                .findAllByCompanyIdOrderByCreatedAtDesc(companyId, PageRequest.of(0, RECENT_SESSION_LIMIT))
                .map(SessionResponse::from)
                .getContent();
    }

    /**
     * Score histogram in ten-point bands.
     *
     * <p>Bands are fixed rather than derived from the data. A histogram whose
     * buckets move with the sample cannot be compared against last month's, and
     * comparing months is the only reason a recruiter looks at this.
     */
    @Transactional(readOnly = true)
    public ScoreDistribution scoreDistribution() {
        UUID companyId = SecurityContext.requireCompanyId();

        List<ScoreBand> bands = new ArrayList<>(10);
        long total = 0;
        for (int lower = 0; lower < 100; lower += 10) {
            // The top band is inclusive of 100 — a perfect score has to land
            // somewhere, and a candidate scoring 100 disappearing from the chart
            // is the kind of off-by-one nobody notices until it is embarrassing.
            int upper = lower == 90 ? 100 : lower + 9;
            long count = reportRepository.countInScoreBand(companyId, (short) lower, (short) upper);
            bands.add(new ScoreBand(lower + "-" + upper, count));
            total += count;
        }
        return new ScoreDistribution(total, bands);
    }

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
