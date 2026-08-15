// =============================================================================
// api/client.ts — Axios instances + JWT interceptors
//
// Two instances:
//   apiClient      — employer portal (Bearer JWT from Zustand authStore)
//   candidateClient — candidate interview room (invite token from URL ?token=)
//
// 401 handling on apiClient uses a "failedQueue" pattern so that if multiple
// concurrent requests all receive a 401, only ONE refresh call is made and all
// queued requests are replayed after it resolves.
//
// KEY CONVENTION:
//   Backend uses SNAKE_CASE for all JSON serialisation.
//   - Request  interceptors convert camelCase  → snake_case before sending.
//   - Response interceptors convert snake_case → camelCase after receiving.
//   This keeps all TypeScript types in camelCase without any per-field mapping.
// =============================================================================

import axios, { AxiosError } from "axios";
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from "axios";
import { authStore } from "@/stores/authStore";

// ── Shared base config ────────────────────────────────────────────────────────

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

// ── snake_case ↔ camelCase deep-key transformers ──────────────────────────────

function snakeToCamel(s: string): string {
  return s.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
}

function camelToSnake(s: string): string {
  return s.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
}

/** Recursively converts all object keys from snake_case to camelCase. */
function toCamelCase(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(toCamelCase);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [
        snakeToCamel(k),
        toCamelCase(v),
      ]),
    );
  }
  return value;
}

/** Recursively converts all object keys from camelCase to snake_case. */
function toSnakeCase(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(toSnakeCase);
  if (value !== null && typeof value === "object" && !(value instanceof FormData)) {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [
        camelToSnake(k),
        toSnakeCase(v),
      ]),
    );
  }
  return value;
}

// ── AppError ──────────────────────────────────────────────────────────────────

export class AppError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly fieldErrors?: Record<string, string>,
    public readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "AppError";
  }

  /** True if a specific form field has a server-side error */
  hasFieldError(field: string): boolean {
    return !!this.fieldErrors?.[field];
  }
}

function toAppError(error: unknown): AppError {
  if (error instanceof AppError) return error;

  if (axios.isAxiosError(error) && error.response) {
    // Transform snake_case error fields to camelCase before reading
    const raw = toCamelCase(error.response.data) as {
      errorCode?: string;
      message?: string;
      // Backend sends fieldErrors as an array: [{ field, message }]
      fieldErrors?: { field: string; message: string }[];
    };

    // Convert [{ field, message }] → { field: message } for form error display
    const fieldErrors =
      Array.isArray(raw.fieldErrors) && raw.fieldErrors.length > 0
        ? Object.fromEntries(raw.fieldErrors.map((fe) => [fe.field, fe.message]))
        : undefined;

    const retryAfterHeader = error.response.headers?.["retry-after"];
    const retryAfterSeconds =
      error.response.status === 429 && retryAfterHeader
        ? parseInt(retryAfterHeader, 10) || undefined
        : undefined;

    return new AppError(
      error.response.status,
      raw.errorCode ?? "API_ERROR",
      raw.message ?? error.message,
      fieldErrors,
      retryAfterSeconds,
    );
  }

  if (error instanceof Error) {
    return new AppError(0, "NETWORK_ERROR", error.message);
  }

  return new AppError(0, "UNKNOWN_ERROR", "An unexpected error occurred");
}

export { toAppError };

// ── failedQueue helpers (for 401 refresh) ────────────────────────────────────

interface QueueEntry {
  resolve: (token: string) => void;
  reject: (err: unknown) => void;
}

let isRefreshing = false;
let failedQueue: QueueEntry[] = [];

function processQueue(error: unknown, token: string | null) {
  failedQueue.forEach((entry) => {
    if (error) {
      entry.reject(error);
    } else {
      entry.resolve(token!);
    }
  });
  failedQueue = [];
}

// ── Employer API client ───────────────────────────────────────────────────────

export const apiClient = axios.create({
  // Required for the HTTP-only refresh cookie to travel with API calls. The SPA
  // is served from app.* and calls api.*, which is cross-site for cookie
  // purposes — and this is also why the server's CORS policy must enumerate
  // origins rather than use a wildcard (PRD v2.1 §7.1.3).
  withCredentials: true,
  baseURL: BASE_URL,
  headers: { "Content-Type": "application/json" },
});

// Request interceptor — attach Bearer token + convert body to snake_case
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = authStore.getState().accessToken;
    if (token) {
      config.headers.set("Authorization", `Bearer ${token}`);
    }
    // Convert camelCase request body keys to snake_case for the backend
    if (
      config.data !== null &&
      config.data !== undefined &&
      typeof config.data === "object" &&
      !(config.data instanceof FormData)
    ) {
      config.data = toSnakeCase(config.data);
    }
    return config;
  },
  (error) => Promise.reject(toAppError(error)),
);

// Response interceptor — unwrap ApiResponse envelope, convert to camelCase, handle 401
apiClient.interceptors.response.use(
  (response) => {
    // Unwrap { success: true, data: T } → T
    if (
      response.data !== null &&
      response.data !== undefined &&
      typeof response.data === "object" &&
      "success" in response.data
    ) {
      response.data = (response.data as { data: unknown }).data ?? null;
    }
    // Convert snake_case response keys to camelCase
    if (response.data !== null && response.data !== undefined) {
      response.data = toCamelCase(response.data);
    }
    return response;
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as AxiosRequestConfig & {
      _retry?: boolean;
    };

    // Only attempt refresh on 401 that we haven't already retried
    if (error.response?.status !== 401 || originalRequest._retry) {
      return Promise.reject(toAppError(error));
    }

    if (isRefreshing) {
      // Queue this request until the ongoing refresh resolves
      return new Promise<string>((resolve, reject) => {
        failedQueue.push({ resolve, reject });
      })
        .then((newToken) => {
          if (originalRequest.headers) {
            (originalRequest.headers as Record<string, string>)[
              "Authorization"
            ] = `Bearer ${newToken}`;
          } else {
            originalRequest.headers = { Authorization: `Bearer ${newToken}` };
          }
          return apiClient(originalRequest);
        })
        .catch((err) => Promise.reject(toAppError(err)));
    }

    // First 401 — kick off a refresh
    originalRequest._retry = true;
    isRefreshing = true;

    try {
      // Company slug must match the backend route: POST /api/v1/{slug}/auth/refresh
      const slug = import.meta.env.VITE_COMPANY_SLUG ?? "interviewiq-dev";

      // Raw axios (not apiClient) to avoid interceptor loops.
      //
      // No body: the refresh token travels in the HTTP-only `iiq_refresh`
      // cookie, which this code cannot read (PRD v2.1 §7.1.1). withCredentials
      // is what makes the browser attach it on a cross-site request — the SPA is
      // on app.* and the API on api.*, and without it the cookie is silently
      // omitted and every session ends at the 60-minute mark.
      const res = await axios.post<{
        success: boolean;
        data: { accessToken: string };
      }>(
        `${BASE_URL}/api/v1/${slug}/auth/refresh`,
        {},
        { withCredentials: true },
      );

      const accessToken = res.data.data.accessToken;
      authStore.getState().setAccessToken(accessToken);

      // Replay all queued requests with the new token
      processQueue(null, accessToken);

      // Replay the original request
      if (originalRequest.headers) {
        (originalRequest.headers as Record<string, string>)[
          "Authorization"
        ] = `Bearer ${accessToken}`;
      } else {
        originalRequest.headers = {
          Authorization: `Bearer ${accessToken}`,
        };
      }
      return apiClient(originalRequest);
    } catch (refreshError) {
      processQueue(refreshError, null);
      // Refresh failed — wipe local auth state and redirect to login
      authStore.getState().clearSession();
      window.location.href = "/login";
      return Promise.reject(toAppError(refreshError));
    } finally {
      isRefreshing = false;
    }
  },
);

// ── Candidate API client ──────────────────────────────────────────────────────
// Reads the invite token from the URL query string on every request.
// No refresh flow — invite tokens don't expire during an interview session.

export const candidateClient = axios.create({
  baseURL: BASE_URL,
  headers: { "Content-Type": "application/json" },
});

candidateClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get("token");
    if (token) {
      config.headers.set("Authorization", `Bearer ${token}`);
    }
    // Convert camelCase request body keys to snake_case for the backend
    if (
      config.data !== null &&
      config.data !== undefined &&
      typeof config.data === "object" &&
      !(config.data instanceof FormData)
    ) {
      config.data = toSnakeCase(config.data);
    }
    return config;
  },
  (error) => Promise.reject(toAppError(error)),
);

candidateClient.interceptors.response.use(
  (response) => {
    // Unwrap { success: true, data: T } → T
    if (
      response.data !== null &&
      response.data !== undefined &&
      typeof response.data === "object" &&
      "success" in response.data
    ) {
      response.data = (response.data as { data: unknown }).data ?? null;
    }
    // Convert snake_case response keys to camelCase
    if (response.data !== null && response.data !== undefined) {
      response.data = toCamelCase(response.data);
    }
    return response;
  },
  (error) => Promise.reject(toAppError(error)),
);
