import { apiClient } from "@/api/client";
import type {
  CreateSessionRequest,
  Evaluation,
  Page,
  ProctoringEvent,
  Session,
} from "@/types";

const BASE = "/api/v1/sessions";

export const sessionsApi = {
  list: (params?: {
    page?: number;
    size?: number;
    status?: string;
    candidateId?: string;
  }) => apiClient.get<Page<Session>>(BASE, { params }).then((r) => r.data),

  get: (sessionId: string) =>
    apiClient.get<Session>(`${BASE}/${sessionId}`).then((r) => r.data),

  create: (data: CreateSessionRequest) =>
    apiClient.post<Session>(BASE, data).then((r) => r.data),

  cancel: (sessionId: string) =>
    apiClient
      .post<Session>(`${BASE}/${sessionId}/cancel`)
      .then((r) => r.data),

  /** What the browser observed during the interview — no verdict attached. */
  getProctoringEvents: (sessionId: string) =>
    apiClient
      .get<ProctoringEvent[]>(`${BASE}/${sessionId}/proctoring`)
      .then((r) => r.data),

  /** The recruiter's private notes. Never sent to a model. */
  saveNotes: (sessionId: string, notes: string) =>
    apiClient.patch(`${BASE}/${sessionId}/notes`, { notes }).then((r) => r.data),

  getRecordingUrl: (sessionId: string) =>
    apiClient
      .get<{ recordingUrl: string }>(`${BASE}/${sessionId}/recording`)
      .then((r) => r.data.recordingUrl),

  /** Fires the resend-or-replace logic; the returned session may be a new one. */
  reinvite: (sessionId: string) =>
    apiClient.post(`${BASE}/${sessionId}/reinvite`).then((r) => r.data),

  getEvaluation: (sessionId: string) =>
    apiClient
      .get<Evaluation>(`${BASE}/${sessionId}/evaluation`)
      .then((r) => r.data),
};
