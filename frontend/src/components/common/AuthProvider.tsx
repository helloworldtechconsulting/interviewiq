// =============================================================================
// AuthProvider.tsx
//
// Runs once on app startup to exchange the HTTP-only refresh cookie for a fresh
// access token. Shows a spinner while that is in flight so ProtectedRoute never
// sees a partially-hydrated state.
//
// The refresh token is NOT readable here — it lives in the `iiq_refresh`
// HTTP-only cookie (PRD v2.1 §7.1.1). So this cannot check whether a session
// exists before asking; it simply calls refresh and lets the server decide. A
// 401 means no session, which is a normal first-visit outcome rather than an
// error worth surfacing.
//
// Flow:
//   1. AuthProvider mounts with no access token in memory.
//   2. Calls authApi.refresh(). The browser attaches the cookie automatically.
//   3. Success → setAccessToken(). Failure → clearSession().
//   4. setHydrated() either way → ProtectedRoute can now decide correctly.
// =============================================================================

import { useEffect, useState } from "react";
import { authApi } from "@/api/modules/auth";
import { authStore } from "@/stores/authStore";
import { LoadingPage } from "./LoadingPage";

interface AuthProviderProps {
  children: React.ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (authStore.getState().accessToken) {
      // Already authenticated in this tab — nothing to exchange.
      authStore.getState().setHydrated();
      setReady(true);
      return;
    }

    authApi
      .refresh()
      .then((data) => {
        authStore.getState().setAccessToken(data.accessToken);
      })
      .catch(() => {
        // No cookie, or it is expired or revoked. Both are ordinary — a first
        // visit looks exactly the same as an expired session from here.
        authStore.getState().clearSession();
      })
      .finally(() => {
        authStore.getState().setHydrated();
        setReady(true);
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (!ready) {
    return <LoadingPage message="Restoring session…" />;
  }

  return <>{children}</>;
}
