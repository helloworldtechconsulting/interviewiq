// =============================================================================
// stores/authStore.ts — Authentication state (Zustand)
//
// SECURITY MODEL (PRD v2.1 §7.1.1, §17)
//
//   • accessToken   — Zustand memory ONLY. Never localStorage, never
//                     sessionStorage, never a cookie readable by script. It is
//                     short-lived (60 minutes) and has to be readable here to be
//                     attached as a bearer header.
//
//   • refreshToken  — NOT HELD BY THIS APPLICATION AT ALL. It lives in the
//                     HTTP-only `iiq_refresh` cookie, which the browser attaches
//                     to /api requests automatically and which JavaScript cannot
//                     read. There is deliberately no field for it below.
//
// This is a change from the previous design, which kept the refresh token in
// localStorage under `iq_refresh_token`. The PRD states the requirement in bold
// — "the refresh token must never be stored in localStorage" — and the risk
// register explains why: one XSS payload turns a 7-day refresh token into a week
// of access from the attacker's own machine, where a stolen 60-minute access
// token is a far smaller window.
//
// On page load, AuthProvider calls the refresh endpoint. If the cookie is
// present and valid the server returns a new access token; if not, the user is
// simply logged out. The client never needs to know which.
// =============================================================================

import { create } from "zustand";
import type { JwtPayload, UserRole } from "@/types";

// Decode JWT payload without any library (base64url → JSON)
function decodeJwt(token: string): JwtPayload | null {
  try {
    const [, payloadB64] = token.split(".");
    // base64url → base64 (replace URL-safe chars, add padding)
    const padded = payloadB64.replace(/-/g, "+").replace(/_/g, "/");
    const json = atob(padded);
    return JSON.parse(json) as JwtPayload;
  } catch {
    return null;
  }
}

// ── Store shape ───────────────────────────────────────────────────────────────

interface AuthUser {
  id: string;
  email: string;
  role: UserRole;
  companyId: string;
}

interface AuthState {
  // State
  accessToken: string | null;
  user: AuthUser | null;
  /** True once AuthProvider has attempted a silent refresh, successful or not. */
  isHydrated: boolean;

  // Actions
  setAccessToken: (accessToken: string) => void;
  clearSession: () => void;
  setHydrated: () => void;
}

// ── Store implementation ──────────────────────────────────────────────────────

export const authStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  isHydrated: false,

  /**
   * Records a new access token. There is no refresh-token parameter by design:
   * the server sets it as an HTTP-only cookie and this code never sees it.
   */
  setAccessToken(accessToken) {
    // Decode user info from the access token payload — no extra API call.
    const payload = decodeJwt(accessToken);
    const user: AuthUser | null = payload
      ? {
          id: payload.sub,
          email: payload.email,
          role: payload.role,
          companyId: payload.cid, // JWT uses the "cid" claim (see TokenService.java)
        }
      : null;

    set({ accessToken, user });
  },

  /**
   * Clears local session state.
   *
   * <p>Does NOT clear the refresh cookie — it cannot, because the cookie is
   * HTTP-only. The logout endpoint revokes it server-side and sends a clearing
   * Set-Cookie, so callers must hit that endpoint rather than relying on this.
   */
  clearSession() {
    set({ accessToken: null, user: null });
  },

  setHydrated() {
    set({ isHydrated: true });
  },
}));

// ── Typed selector hooks (avoids full re-renders on unrelated state changes) ──

export const useAuthUser = () => authStore((s) => s.user);
export const useIsAuthenticated = () => authStore((s) => !!s.accessToken);
export const useIsHydrated = () => authStore((s) => s.isHydrated);
export const useUserRole = () => authStore((s) => s.user?.role ?? null);
