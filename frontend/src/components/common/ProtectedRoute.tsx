// =============================================================================
// ProtectedRoute.tsx
//
// Wraps employer portal routes.  Redirects to /login if not authenticated.
// Optionally restricts to specific roles (e.g. ADMIN-only pages).
//
// Usage (as wrapper element — renders <Outlet />):
//   <Route element={<ProtectedRoute />}>…</Route>
//
// Usage (with children — for inline role-guarding in the router config):
//   <ProtectedRoute allowedRoles={["ADMIN"]}><TeamPage /></ProtectedRoute>
// =============================================================================

import type { ReactNode } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useIsAuthenticated, useIsHydrated, useUserRole } from "@/stores/authStore";
import type { UserRole } from "@/types";
import { LoadingPage } from "./LoadingPage";

interface ProtectedRouteProps {
  /** If provided, only users with one of these roles can access the route */
  allowedRoles?: UserRole[];
  /** Optional children — if omitted renders <Outlet /> instead */
  children?: ReactNode;
}

export function ProtectedRoute({ allowedRoles, children }: ProtectedRouteProps) {
  const isHydrated = useIsHydrated();
  const isAuthenticated = useIsAuthenticated();
  const role = useUserRole();
  const location = useLocation();

  // Wait until the auth store has restored from localStorage
  if (!isHydrated) {
    return <LoadingPage />;
  }

  if (!isAuthenticated) {
    // Preserve the intended destination so we can redirect after login
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (allowedRoles && role && !allowedRoles.includes(role)) {
    // Authenticated but wrong role — send to dashboard (not a 404)
    return <Navigate to="/app/dashboard" replace />;
  }

  // Render children (inline usage) or <Outlet /> (route wrapper usage)
  return <>{children ?? <Outlet />}</>;
}
