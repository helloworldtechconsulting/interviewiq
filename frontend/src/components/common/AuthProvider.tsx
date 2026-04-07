// =============================================================================
// AuthProvider.tsx
//
// Runs once on app startup to exchange the stored refresh token for a fresh
// access token.  Shows a loading spinner while the exchange is in-flight so
// ProtectedRoute never sees a partially-hydrated state.
//
// Flow:
//   1. hydrateFromStorage() (called in main.tsx) reads refreshToken from
//      localStorage and puts it in the Zustand store (isHydrated = false).
//   2. AuthProvider mounts, detects refreshToken present but accessToken null.
//   3. Calls authApi.refresh() — on success: setTokens(); on failure: logout().
//   4. Calls setHydrated() regardless → isHydrated = true.
//   5. Renders children (the router) — ProtectedRoute can now decide correctly.
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
    const { refreshToken, accessToken } = authStore.getState();

    if (!refreshToken || accessToken) {
      // No stored session, or already authenticated — nothing to exchange.
      authStore.getState().setHydrated();
      setReady(true);
      return;
    }

    authApi
      .refresh({ refreshToken })
      .then((data) => {
        authStore.getState().setTokens(data.accessToken, data.refreshToken);
      })
      .catch(() => {
        // Refresh token expired or revoked — wipe stale state.
        authStore.getState().logout();
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
