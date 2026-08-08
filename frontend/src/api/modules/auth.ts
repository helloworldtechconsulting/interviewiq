// =============================================================================
// api/modules/auth.ts — Authentication API calls
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

  refresh: (data: RefreshRequest, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/refresh`, data)
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

  logout: () => {
    const refreshToken = authStore.getState().refreshToken ?? "";
    return apiClient
      .post(`${base()}/logout`, { refreshToken })
      .then(() => undefined);
  },

  googleLogin: (idToken: string, slug?: string) =>
    apiClient
      .post<AuthResponse>(`${base(slug)}/google`, { idToken })
      .then((r) => r.data),

  googleRegister: (idToken: string, companyName: string) =>
    apiClient
      .post<AuthResponse>("/api/v1/auth/google/register", { idToken, companyName })
      .then((r) => r.data),
};
