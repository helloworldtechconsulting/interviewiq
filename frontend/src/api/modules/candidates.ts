import { apiClient } from "@/api/client";
import type {
  Candidate,
  CreateCandidateRequest,
  Page,
  ResumeUploadUrlResponse,
  UpdateCandidateRequest,
} from "@/types";

const BASE = "/api/v1/candidates";

export const candidatesApi = {
  list: (params?: {
    jobOpeningId?: string;   // optional — omit for company-wide listing
    page?: number;
    size?: number;
  }) => apiClient.get<Page<Candidate>>(BASE, { params }).then((r) => r.data),

  get: (candidateId: string) =>
    apiClient.get<Candidate>(`${BASE}/${candidateId}`).then((r) => r.data),

  create: (data: CreateCandidateRequest) =>
    apiClient.post<Candidate>(BASE, data).then((r) => r.data),

  update: (candidateId: string, data: UpdateCandidateRequest) =>
    apiClient
      .patch<Candidate>(`${BASE}/${candidateId}`, data)
      .then((r) => r.data),

  delete: (candidateId: string) =>
    apiClient.delete(`${BASE}/${candidateId}`).then((r) => r.data),

  getResumeUploadUrl: (
    candidateId: string,
    filename: string,
    contentType: string,
  ) =>
    apiClient
      .get<ResumeUploadUrlResponse>(`${BASE}/${candidateId}/resume-upload-url`, {
        params: { filename, contentType },
      })
      .then((r) => r.data),

  sendInvite: (sessionId: string) =>
      apiClient
          .post(`/api/v1/sessions/${sessionId}/resend`)
          .then((r) => r.data),
};
