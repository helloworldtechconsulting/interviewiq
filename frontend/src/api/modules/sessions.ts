import { apiClient } from "@/api/client";
import type {
  CreateSessionRequest,
  Evaluation,
  Page,
  Session,
  SetMeetUrlRequest,
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

  setMeetUrl: (sessionId: string, data: SetMeetUrlRequest) =>
    apiClient
      .patch<Session>(`${BASE}/${sessionId}/meet-url`, data)
      .then((r) => r.data),

  cancel: (sessionId: string) =>
    apiClient
      .post<Session>(`${BASE}/${sessionId}/cancel`)
      .then((r) => r.data),

  getEvaluation: (sessionId: string) =>
    apiClient
      .get<Evaluation>(`${BASE}/${sessionId}/evaluation`)
      .then((r) => r.data),
};
