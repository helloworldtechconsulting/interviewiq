import { apiClient } from "@/api/client";
import type { Session } from "@/types";

const BASE = "/api/v1/dashboard";

/**
 * Dashboard counters (INTIQ-73).
 *
 * These replace numbers the page used to compute for itself by requesting four
 * lists with `size=1` and reading `totalElements` off the page envelope. That
 * was cheap and answered the wrong question — a list count says how many rows
 * match a query, not how many interviews are pending, so cancelled and expired
 * sessions were counted alongside live ones.
 */

export interface DashboardStats {
  activeJobs: number;
  totalCandidates: number;
  /** Invited, scheduled, running or being scored — what is actually in flight. */
  pendingInterviews: number;
  completedInterviews: number;
  /** Completed reports nobody has opened. The one number here that prompts an action. */
  reportsAwaitingReview: number;
  /** Booked and not attended, last 30 days. */
  noShows: number;
}

export interface ScoreBand {
  bandLabel: string;
  count: number;
}

export interface ScoreDistribution {
  /**
   * Sample size, carried alongside the bands on purpose — a histogram of four
   * interviews looks identical in shape to one of four hundred.
   */
  totalScored: number;
  bands: ScoreBand[];
}

export const dashboardApi = {
  stats: () => apiClient.get<DashboardStats>(`${BASE}/stats`).then((r) => r.data),

  recentSessions: () =>
    apiClient.get<Session[]>(`${BASE}/recent-sessions`).then((r) => r.data),

  scoreDistribution: () =>
    apiClient
      .get<ScoreDistribution>(`${BASE}/score-distribution`)
      .then((r) => r.data),
};
