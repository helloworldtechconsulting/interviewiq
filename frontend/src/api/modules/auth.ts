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

// Company slug is required by the backend: POST /api/v1/{slug}/auth/*
// Set VITE_COMPANY_SLUG in .env.local (default: "interviewiq-dev" for local dev)
const SLUG = import.meta.env.VITE_COMPANY_SLUG ?? "interviewiq-dev";
const BASE = `/api/v1/${SLUG}/auth`;

export const authApi = {
  register: (data: RegisterRequest) =>
    apiClient.post(`${BASE}/register`, data).then(() => undefined),

  login: (data: LoginRequest) =>
    apiClient.post<AuthResponse>(`${BASE}/login`, data).then((r) => r.data),

  refresh: (data: RefreshRequest) =>
    apiClient.post<AuthResponse>(`${BASE}/refresh`, data).then((r) => r.data),

  verifyEmail: (data: VerifyOtpRequest) =>
    apiClient.post<AuthResponse>(`${BASE}/verify-email`, data).then((r) => r.data),

  resendVerification: (data: ResendVerificationRequest) =>
    apiClient.post(`${BASE}/resend-verification`, data).then(() => undefined),

  forgotPassword: (data: ForgotPasswordRequest) =>
    apiClient.post(`${BASE}/forgot-password`, data).then(() => undefined),

  resetPassword: (data: ResetPasswordRequest) =>
    apiClient.post(`${BASE}/reset-password`, data).then(() => undefined),

  logout: () => {
    const refreshToken = authStore.getState().refreshToken ?? "";
    return apiClient.post(`${BASE}/logout`, { refreshToken }).then(() => undefined);
  },
};
