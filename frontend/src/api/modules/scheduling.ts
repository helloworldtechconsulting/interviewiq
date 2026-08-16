// =============================================================================
// api/modules/scheduling.ts — candidate-driven scheduling (PRD v2.1 §7.4, §11)
//
// Four endpoints replace the entire employer slot-administration group that v2.1
// deletes. Every call is on the candidate client, authenticated by the
// session-scoped invite token — the session id comes from that token, never from
// the URL, so there is no session parameter on any of these.
// =============================================================================

import { candidateClient } from "@/api/client";
import type { DurationTier } from "@/types";

const BASE = "/api/v1/candidate/scheduling";

/**
 * The readiness gate (§7.4.3) — what replaced the 30-minute invite buffer.
 *
 * The buffer was always a proxy for "have the questions finished generating?",
 * so readiness is now measured directly. The invite page polls this while
 * questions generate.
 */
export interface Readiness {
  /** Question generation has completed. */
  questionsReady: boolean;
  /** A slot starting now would fit within platform capacity. */
  capacityAvailable: boolean;
  /** Both of the above — "Start now" may be offered. */
  canStartNow: boolean;
  /** Next bucket with room, or null if the whole horizon is full. */
  earliestBookableAt: string | null;
  durationMinutes: number;
  durationTier: DurationTier;
}

/**
 * Bookable times.
 *
 * Availability is genuinely 24x7 (§7.4.2). If a time is missing from this list
 * it is because the platform is at capacity for that moment, and for no other
 * reason — there are no business hours, blackout periods or quiet hours.
 */
export interface AvailableTimes {
  from: string;
  until: string;
  durationMinutes: number;
  availableStartTimes: string[];
}

export interface Booking {
  scheduledStartAt: string;
  durationMinutes: number;
  status: string;
  icsDownloadUrl: string;
}

export const schedulingApi = {
  readiness: () =>
    candidateClient.get<Readiness>(`${BASE}/readiness`).then((r) => r.data),

  availableTimes: (params?: { from?: string; until?: string }) =>
    candidateClient
      .get<AvailableTimes>(`${BASE}/available-times`, { params })
      .then((r) => r.data),

  book: (startAt: string) =>
    candidateClient.post<Booking>(`${BASE}/book`, { startAt }).then((r) => r.data),

  /**
   * Moves an existing booking. The invite token and the Rs.100 reservation are
   * unaffected — rescheduling is a calendar change, not a billing event (§7.4.1).
   */
  reschedule: (startAt: string) =>
    candidateClient.put<Booking>(`${BASE}/book`, { startAt }).then((r) => r.data),
};
