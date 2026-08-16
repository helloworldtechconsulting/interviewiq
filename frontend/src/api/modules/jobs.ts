import { apiClient } from "@/api/client";
import type {
  CreateJobRequest,
  JdUploadUrlResponse,
  Job,
  Page,
  UpdateJobRequest,
} from "@/types";

const BASE = "/api/v1/jobs";

export const jobsApi = {
  /** `search` matches title or department, server-side across all pages. */
  list: (params?: {
    page?: number;
    size?: number;
    status?: string;
    search?: string;
  }) => apiClient.get<Page<Job>>(BASE, { params }).then((r) => r.data),

  get: (jobId: string) =>
    apiClient.get<Job>(`${BASE}/${jobId}`).then((r) => r.data),

  create: (data: CreateJobRequest) =>
    apiClient.post<Job>(BASE, data).then((r) => r.data),

  update: (jobId: string, data: UpdateJobRequest) =>
    apiClient.patch<Job>(`${BASE}/${jobId}`, data).then((r) => r.data),

  delete: (jobId: string) =>
    apiClient.delete(`${BASE}/${jobId}`).then((r) => r.data),

  getJdUploadUrl: (jobId: string, filename: string, contentType: string) =>
    apiClient
      .get<JdUploadUrlResponse>(`${BASE}/${jobId}/jd-upload-url`, {
        params: { filename, contentType },
      })
      .then((r) => r.data),
};
