import { apiClient } from "@/api/client";
import type {
  CompanyOnboardRequest,
  Company,
  OnboardResponse,
  UpdateCompanyRequest,
} from "@/types";

export const companiesApi = {
  onboard: (data: CompanyOnboardRequest) =>
    apiClient
      .post<OnboardResponse>("/api/v1/companies/register", data)
      .then((r) => r.data),

  checkSlug: (slug: string) =>
    apiClient
      .get<{ available: boolean }>("/api/v1/companies/check-slug", {
        params: { slug },
      })
      .then((r) => r.data),

  getProfile: () =>
    apiClient.get<Company>("/api/v1/companies/me").then((r) => r.data),

  updateProfile: (data: UpdateCompanyRequest) =>
    apiClient
      .patch<Company>("/api/v1/companies/me", data)
      .then((r) => r.data),
};
