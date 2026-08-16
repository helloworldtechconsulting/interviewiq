// =============================================================================
// stores/authStore.ts — Authentication state (Zustand)
//
// Security model:
//   • accessToken  — kept in Zustand memory ONLY (never localStorage/sessionStorage)
//                    XSS cannot steal it because it's not in the DOM
//   • refreshToken — stored in localStorage (key: iq_refresh_token)
//                    survives page refresh; needed to restore session
//
// On page load (main.tsx) we call authStore.getState().hydrateFromStorage()
// to restore the refresh token and exchange it for a new access token.
// =============================================================================

import { create } from "zustand";
import type { JwtPayload, UserRole } from "@/types";

const REFRESH_TOKEN_KEY = "iq_refresh_token";

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
  refreshToken: string | null;
  user: AuthUser | null;
  isHydrated: boolean;   // true once hydrateFromStorage() has run

  // Actions
  setTokens: (accessToken: string, refreshToken: string) => void;
  logout: () => void;
  hydrateFromStorage: () => void;
  setHydrated: () => void;
}

// ── Store implementation ──────────────────────────────────────────────────────

export const authStore = create<AuthState>((set) => ({
  accessToken: null,
  refreshToken: null,
  user: null,
  isHydrated: false,

  setTokens(accessToken, refreshToken) {
    // Persist refresh token to localStorage
    try {
      localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
    } catch {
      // localStorage may be unavailable in private mode — not fatal
    }

    // Decode user info from access token payload (no extra API call)
    const payload = decodeJwt(accessToken);
    const user: AuthUser | null = payload
      ? {
          id: payload.sub,
          email: payload.email,
          role: payload.role,
          companyId: payload.cid,   // JWT uses "cid" claim (see TokenService.java)
        }
      : null;

    set({ accessToken, refreshToken, user });
  },

  logout() {
    try {
      localStorage.removeItem(REFRESH_TOKEN_KEY);
    } catch {
      // ignore
    }
    set({ accessToken: null, refreshToken: null, user: null });
  },

  hydrateFromStorage() {
    let refreshToken: string | null = null;
    try {
      refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    } catch {
      // ignore
    }
    // Do NOT set isHydrated here — AuthProvider completes hydration
    // after attempting to exchange the refresh token for an access token.
    set({ refreshToken });
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
