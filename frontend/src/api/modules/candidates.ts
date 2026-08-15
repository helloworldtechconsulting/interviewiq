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
  /**
   * All filters are applied server-side. `search` matches name or email across
   * the whole company, not just the page currently loaded — filtering in the
   * browser silently hid anyone past the first page, which on a 200-candidate
   * bulk import is most of them.
   */
  list: (params?: {
    jobOpeningId?: string;   // optional — omit for company-wide listing
    resumeStatus?: string;   // PENDING | IN_PROGRESS | DONE | FAILED
    search?: string;
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

  sendInvite: (candidateId: string) =>
    apiClient
      .post(`${BASE}/${candidateId}/invite`)
      .then((r) => r.data),
};
