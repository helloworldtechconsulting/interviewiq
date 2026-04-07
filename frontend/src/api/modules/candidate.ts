// Candidate interview room API — uses candidateClient (invite token from URL)
import { candidateClient } from "@/api/client";
import type { CandidateQuestion, CandidateSession, SubmitAnswerRequest } from "@/types";

const BASE = "/api/v1/candidate";

export const candidateRoomApi = {
  getSession: (sessionId: string) =>
    candidateClient
      .get<CandidateSession>(`${BASE}/sessions/${sessionId}`)
      .then((r) => r.data),

  getQuestions: (sessionId: string) =>
    candidateClient
      .get<CandidateQuestion[]>(`${BASE}/sessions/${sessionId}/questions`)
      .then((r) => r.data),

  submitAnswer: (sessionId: string, data: SubmitAnswerRequest) =>
    candidateClient
      .post(`${BASE}/sessions/${sessionId}/answers`, data)
      .then((r) => r.data),
};
