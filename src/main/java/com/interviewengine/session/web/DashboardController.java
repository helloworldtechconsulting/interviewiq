package com.interviewengine.session.web;

import com.interviewengine.session.dto.DashboardDtos.DashboardStats;
import com.interviewengine.session.dto.DashboardDtos.ScoreDistribution;
import com.interviewengine.session.dto.SessionResponse;
import com.interviewengine.session.service.DashboardService;
import com.interviewengine.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Employer dashboard (PRD v2.1 §7.7, INTIQ-73).
 *
 * <ul>
 *   <li>{@code GET /api/v1/dashboard/stats}              — headline counters</li>
 *   <li>{@code GET /api/v1/dashboard/recent-sessions}    — five most recent</li>
 *   <li>{@code GET /api/v1/dashboard/score-distribution} — score histogram</li>
 * </ul>
 *
 * <p>Three endpoints rather than one composite payload, so the page can render
 * its counters immediately and let the histogram — the slowest of the three and
 * the least urgent — arrive when it arrives.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public ApiResponse<DashboardStats> stats() {
        return ApiResponse.ok(dashboardService.stats());
    }

    @GetMapping("/recent-sessions")
    public ApiResponse<List<SessionResponse>> recentSessions() {
        return ApiResponse.ok(dashboardService.recentSessions());
    }

    @GetMapping("/score-distribution")
    public ApiResponse<ScoreDistribution> scoreDistribution() {
        return ApiResponse.ok(dashboardService.scoreDistribution());
    }
}
