import { apiClient } from "@/api/client";
import type { Page } from "@/types";

const BASE = "/api/v1/internal";

/**
 * Platform-staff endpoints (INTIQ-35).
 *
 * Every call here crosses the tenant boundary and is gated on PLATFORM_STAFF
 * server-side. The UI hides the section from everyone else, but that is
 * convenience — the authorisation is the `@PreAuthorize` on the controller, not
 * the absence of a nav link.
 */

export interface AdminCompanyRow {
  companyId: string;
  name: string;
  slug: string;
  status: "ACTIVE" | "INACTIVE" | "SUSPENDED";
  createdAt: string;
  interviewsCompleted: number;
  interviewsPending: number;
  balancePaise: number;
  promoBalancePaise: number;
  reservedPaise: number;
  lifetimeSpendPaise: number;
}

export interface PlatformStats {
  activeCompanies: number;
  interviewsCompleted: number;
  interviewsPending: number;
  grossRevenuePaise: number;
  outstandingPromoPaise: number;
  outstandingReservationsPaise: number;
}

export interface RetiredQuestion {
  id: string;
  jobOpeningId: string;
  bankQuestionId: string;
  questionText: string;
  timesAsked: number;
  timesSkipped: number;
  shortAnswers: number;
  candidateFlags: number;
  scoredCount: number;
  scoreMean: number;
  retiredAt: string;
  retiredReason:
    | "HIGH_SKIP_RATE"
    | "SHORT_ANSWERS"
    | "NO_SCORE_VARIANCE"
    | "CANDIDATE_FLAGGED"
    | "MANUAL";
}

export const adminApi = {
  listCompanies: (params?: { page?: number; size?: number }) =>
    apiClient
      .get<Page<AdminCompanyRow>>(`${BASE}/companies`, { params })
      .then((r) => r.data),

  platformStats: () =>
    apiClient.get<PlatformStats>(`${BASE}/stats`).then((r) => r.data),

  /**
   * Adds paid balance to a company's wallet. The reason is required at 10
   * characters minimum server-side — see the endpoint for why "fix" is not
   * good enough.
   */
  manualCredit: (data: {
    companyId: string;
    amountPaise: number;
    reason: string;
  }) => apiClient.post(`${BASE}/manual-credit`, data).then((r) => r.data),

  retiredQuestions: () =>
    apiClient
      .get<RetiredQuestion[]>(`${BASE}/questions/retired`)
      .then((r) => r.data),

  /** How many questions the current thresholds would retire, without retiring any. */
  retirementPreview: () =>
    apiClient
      .get<{ wouldRetire: number }>(`${BASE}/questions/retirement-preview`)
      .then((r) => r.data),

  reinstateQuestion: (telemetryId: string) =>
    apiClient
      .post(`${BASE}/questions/${telemetryId}/reinstate`)
      .then((r) => r.data),

  promoExposure: () =>
    apiClient
      .get<{ totalPromotionalExposurePaise: number }>(`${BASE}/promo-exposure`)
      .then((r) => r.data),
};
