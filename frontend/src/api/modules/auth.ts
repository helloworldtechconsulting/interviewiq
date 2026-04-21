// =============================================================================
// api/modules/auth.ts — Authentication API calls
//
// All authenticated employer endpoints live under /api/v1/{slug}/auth/*.
// The slug identifies which company the user belongs to.
//
// SLUG resolution order:
//   1. Explicit `slug` argument (passed from navigation state after onboarding)
//   2. VITE_COMPANY_SLUG env var (set in .env.local per deployment)
//   3. Fallback "interviewiq-dev" (local dev seed company)
// =============================================================================

import { apiClient } from "@/api/client";
import { authStore } from "@/stores/authStore";
import type {
  AuthResponse,
  ForgotPasswordRequest,
  LoginRequest,
  RefreshRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  VerifyOtpRequest,
} from "@/types";

/** Default slug for the current deployment (configurable per environment). */
const DEFAULT_SLUG = import.meta.env.VITE_COMPANY_SLUG ?? "interviewiq-dev";

/** Returns the base auth path for a given slug (or the deployment default). */
function base(slug?: string) {
  return `/api/v1/${slug ?? DEFAULT_SLUG}/auth`;
}

export const authApi = {
  /**
   * Adds a new user to an EXISTING company.
   * For creating a brand-new company, use companiesApi.onboard() instead.
   *
   * POST /api/v1/{slug}/auth/register
   */
  register: (data: RegisterRequest, slug?: string) =>
    apiClient.post(`${base(slug)}/register`, data).then(() => undefined),

  /** POST /api/v1/{slug}/auth/login */
  login: (data: LoginRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/login`, data)
      .then((r) => r.data),

  /** POST /api/v1/{slug}/auth/refresh */
  refresh: (data: RefreshRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/refresh`, data)
      .then((r) => r.data),

  /**
   * Verifies the email OTP and returns a token pair.
   * After onboarding, pass the slug returned by companiesApi.onboard().
   *
   * POST /api/v1/{slug}/auth/verify-email
   */
  verifyEmail: (data: VerifyOtpRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/verify-email`, data)
      .then((r) => r.data),

  /** POST /api/v1/{slug}/auth/resend-verification */
  resendVerification: (data: ResendVerificationRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/resend-verification`, data)
      .then(() => undefined),

  /** POST /api/v1/{slug}/auth/forgot-password */
  forgotPassword: (data: ForgotPasswordRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/forgot-password`, data)
      .then(() => undefined),

  /** POST /api/v1/{slug}/auth/reset-password */
  resetPassword: (data: ResetPasswordRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/reset-password`, data)
      .then(() => undefined),

  /** POST /api/v1/{slug}/auth/logout — revokes all refresh tokens for the user. */
  logout: () => {
    const refreshToken = authStore.getState().refreshToken ?? "";
    // Slug doesn't matter for logout (token is looked up by hash, not slug)
    return apiClient
      .post(`${base()}/logout`, { refreshToken })
      .then(() => undefined);
  },
};
