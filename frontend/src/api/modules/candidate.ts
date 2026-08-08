import { candidateClient } from "@/api/client";
import type { InterviewInitData, Session } from "@/types";

const BASE = "/api/v1/candidate";

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

export const candidateRoomApi = {
  getSession: () =>
    candidateClient
      .get<Session>(`${BASE}/session`)
      .then((r) => r.data),

  initInterview: () =>
    candidateClient
      .get<InterviewInitData>(`${BASE}/interview/init`)
      .then((r) => r.data),

  startInterview: () =>
    candidateClient
      .post<Session>(`${BASE}/interview/start`)
      .then((r) => r.data),

  completeInterview: (payload: CompleteInterviewPayload) =>
    candidateClient
      .post<Session>(`${BASE}/interview/complete`, payload)
      .then((r) => r.data),

  reportError: (reason: string) =>
    candidateClient
      .post<Session>(`${BASE}/interview/error?reason=${encodeURIComponent(reason)}`)
      .then((r) => r.data),

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

  googleVerify: (idToken: string) =>
    candidateClient
      .post(`${BASE}/auth/google`, { idToken })
      .then(() => undefined),
};
