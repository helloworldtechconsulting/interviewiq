// =============================================================================
// api/modules/auth.ts — Authentication API calls
// =============================================================================

import { apiClient } from "@/api/client";
import type {
  AuthResponse,
  ForgotPasswordRequest,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  VerifyOtpRequest,
} from "@/types";

const DEFAULT_SLUG = import.meta.env.VITE_COMPANY_SLUG ?? "interviewiq-dev";

function base(slug?: string) {
  const resolved = typeof slug === "string" && slug.length > 0 ? slug : DEFAULT_SLUG;
  return `/api/v1/${resolved}/auth`;
}

export const authApi = {
  register: (data: RegisterRequest, slug?: string) =>
    apiClient.post(`${base(slug)}/register`, data).then(() => undefined),

  login: (data: LoginRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/login`, data)
      .then((r) => r.data),


  verifyEmail: (data: VerifyOtpRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/verify-email`, data)
      .then((r) => r.data),

  resendVerification: (data: ResendVerificationRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/resend-verification`, data)
      .then(() => undefined),

  forgotPassword: (data: ForgotPasswordRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/forgot-password`, data)
      .then(() => undefined),

  resetPassword: (data: ResetPasswordRequest, slug?: string) =>
    apiClient
      .post(`${base(slug)}/reset-password`, data)
      .then(() => undefined),

  /**
   * Revokes the session server-side and clears the refresh cookie.
   *
   * <p>No token is sent: the server reads the HTTP-only cookie. Calling this is
   * the ONLY way to clear it — clearSession() in the store cannot, because
   * script has no access to an HttpOnly cookie by design.
   */
  logout: () =>
    apiClient.post(`${base()}/logout`).then(() => undefined),

  /**
   * Exchanges the refresh cookie for a new access token.
   *
   * <p>Takes no argument for the same reason: the browser attaches the cookie.
   */
  refresh: () =>
    apiClient.post<AuthResponse>(`${base()}/refresh`).then((r) => r.data),

  googleLogin: (idToken: string, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/google`, { idToken })
      .then((r) => r.data),

  googleRegister: (idToken: string, companyName: string) =>
    apiClient
      .post<AuthResponse>("/api/v1/auth/google/register", { idToken, companyName })
      .then((r) => r.data),
};
