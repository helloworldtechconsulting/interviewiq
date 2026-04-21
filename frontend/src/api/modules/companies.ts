// =============================================================================
// api/modules/companies.ts — Company management API calls
//
// PUBLIC endpoints (no auth required):
//   POST /api/v1/companies/register  — onboard a new company + admin + wallet
//   GET  /api/v1/companies/check-slug — slug availability check
//
// AUTHENTICATED endpoints:
//   GET   /api/v1/companies/me — get company profile
//   PATCH /api/v1/companies/me — update company profile (ADMIN only)
// =============================================================================

import { apiClient } from "@/api/client";
import type {
  CompanyOnboardRequest,
  Company,
  OnboardResponse,
  UpdateCompanyRequest,
} from "@/types";

export const companiesApi = {
  /**
   * Creates a new company + first admin user + empty wallet in one transaction.
   * Returns the auto-generated slug and email so the caller can redirect to
   * the verify-email page with the correct slug.
   *
   * POST /api/v1/companies/register
   */
  onboard: (data: CompanyOnboardRequest) =>
    apiClient
      .post<OnboardResponse>("/api/v1/companies/register", data)
      .then((r) => r.data),

  /**
   * Returns { available: true/false } for a slug candidate.
   * Used to give instant feedback as the user types during onboarding.
   *
   * GET /api/v1/companies/check-slug?slug=acme-corp
   */
  checkSlug: (slug: string) =>
    apiClient
      .get<{ available: boolean }>("/api/v1/companies/check-slug", {
        params: { slug },
      })
      .then((r) => r.data),

  /**
   * Returns the authenticated employer's company profile.
   *
   * GET /api/v1/companies/me
   */
  getProfile: () =>
    apiClient.get<Company>("/api/v1/companies/me").then((r) => r.data),

  /**
   * Partially updates the company profile (ADMIN only).
   * Fields omitted or set to null are left unchanged.
   *
   * PATCH /api/v1/companies/me
   */
  updateProfile: (data: UpdateCompanyRequest) =>
    apiClient
      .patch<Company>("/api/v1/companies/me", data)
      .then((r) => r.data),
};
