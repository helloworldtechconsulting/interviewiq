import { apiClient } from "@/api/client";
import type { Company, LogoUploadUrlResponse, UpdateCompanyRequest } from "@/types";

// Backend: @RequestMapping("/api/v1/companies")
// Authenticated routes: GET /me, PATCH /me, GET /logo-upload-url
const BASE = "/api/v1/companies";

export const companyApi = {
  getMyCompany: () =>
    apiClient.get<Company>(`${BASE}/me`).then((r) => r.data),

  update: (data: UpdateCompanyRequest) =>
    apiClient.patch<Company>(`${BASE}/me`, data).then((r) => r.data),

  getLogoUploadUrl: (filename: string, contentType: string) =>
    apiClient
      .get<LogoUploadUrlResponse>(`${BASE}/logo-upload-url`, {
        params: { filename, contentType },
      })
      .then((r) => r.data),
};
