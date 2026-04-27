// Candidate interview room API — uses candidateClient (invite token from URL ?token=)
//
// All endpoints are under /api/v1/candidate/* and require the invite JWT that
// CandidateTokenAuthFilter validates in the Spring Security filter chain.

import { candidateClient } from "@/api/client";
import type { InterviewInitData, Session } from "@/types";

const BASE = "/api/v1/candidate";

// ── Request/response payload types ───────────────────────────────────────────

export interface QuestionAnswer {
  questionOrder: number;
  transcript: string;
}

export interface ProctoringFlagPayload {
  type: string;
  count: number;
  firstOccurrence: string;
}

export interface CompleteInterviewPayload {
  answers: QuestionAnswer[];
  proctoringFlags: ProctoringFlagPayload[];
  recordingS3Key: string | null;
}

// ── API surface ───────────────────────────────────────────────────────────────

export const candidateRoomApi = {
  /** GET /api/v1/candidate/session — lightweight session status poll */
  getSession: () =>
    candidateClient
      .get<Session>(`${BASE}/session`)
      .then((r) => r.data),

  /**
   * GET /api/v1/candidate/interview/init
   * Returns questions JSON, recording upload URL, and session metadata.
   * Poll until questionGenerationStatus === 'DONE' before starting the interview.
   */
  initInterview: () =>
    candidateClient
      .get<InterviewInitData>(`${BASE}/interview/init`)
      .then((r) => r.data),

  /**
   * POST /api/v1/candidate/interview/start
   * Transitions session INVITED → STARTED. Call once camera/mic are confirmed.
   * Idempotent — safe to call again after browser refresh (already-STARTED session
   * returns current state without error).
   */
  startInterview: () =>
    candidateClient
      .post<Session>(`${BASE}/interview/start`)
      .then((r) => r.data),

  /**
   * POST /api/v1/candidate/interview/complete
   * Submits all answers + proctoring flags. Transitions session STARTED → COMPLETED
   * and triggers AI evaluation pipeline.
   */
  completeInterview: (payload: CompleteInterviewPayload) =>
    candidateClient
      .post<Session>(`${BASE}/interview/complete`, payload)
      .then((r) => r.data),

  /**
   * POST /api/v1/candidate/interview/error?reason=...
   * Called on fatal browser error. Transitions session to ERROR and releases
   * the billing reservation.
   */
  reportError: (reason: string) =>
    candidateClient
      .post<Session>(`${BASE}/interview/error?reason=${encodeURIComponent(reason)}`)
      .then((r) => r.data),

  /**
   * Uploads the recorded WebM blob directly to S3 via the pre-signed PUT URL.
   * This call bypasses our backend — it's a direct S3 PUT.
   * Returns true on success, false on failure (caller handles gracefully).
   */
  uploadRecording: async (presignedUrl: string, blob: Blob): Promise<boolean> => {
    try {
      const res = await fetch(presignedUrl, {
        method: "PUT",
        headers: { "Content-Type": "video/webm" },
        body: blob,
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  /**
   * Verifies the candidate's Google identity by submitting a Google ID token.
   *
   * POST /api/v1/candidate/auth/google
   *
   * Requires an active invite token (handled by the candidate security chain).
   * Sets googleVerified = true on the candidate record. The frontend should
   * call this during the GOOGLE_AUTH phase before allowing the setup phase.
   */
  googleVerify: (idToken: string) =>
    candidateClient
      .post(`${BASE}/auth/google`, { idToken })
      .then(() => undefined),
};
