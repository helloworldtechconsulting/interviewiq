// =============================================================================
// router/index.tsx — Full route tree
//
// All employer pages are lazily loaded. Candidate room pages too.
// Public auth pages are eagerly imported (small, needed immediately).
// =============================================================================

import { lazy, Suspense } from "react";
import { createBrowserRouter, Navigate } from "react-router-dom";

import { PublicLayout } from "@/layouts/PublicLayout";
import { AppLayout } from "@/layouts/AppLayout";
import { InterviewLayout } from "@/layouts/InterviewLayout";
import { ProtectedRoute } from "@/components/common/ProtectedRoute";
import { LoadingPage } from "@/components/common/LoadingPage";

// ── Auth pages (eagerly loaded — small) ──────────────────────────────────────
import { OnboardingPage } from "@/pages/auth/OnboardingPage";
import { LoginPage } from "@/pages/auth/LoginPage";
import { VerifyEmailPage } from "@/pages/auth/VerifyEmailPage";
import { ForgotPasswordPage } from "@/pages/auth/ForgotPasswordPage";
import { ResetPasswordPage } from "@/pages/auth/ResetPasswordPage";


const AdminPage = lazy(() =>
    import("@/pages/employer/admin/AdminPage").then((m) => ({
      default: m.AdminPage,
    })),
);
// ── Employer pages (lazily loaded) ────────────────────────────────────────────
const DashboardPage = lazy(() =>
  import("@/pages/employer/dashboard/DashboardPage").then((m) => ({
    default: m.DashboardPage,
  })),
);
const JobsPage = lazy(() =>
  import("@/pages/employer/jobs/JobsPage").then((m) => ({
    default: m.JobsPage,
  })),
);
const JobDetailPage = lazy(() =>
  import("@/pages/employer/jobs/JobDetailPage").then((m) => ({
    default: m.JobDetailPage,
  })),
);
const CandidatesPage = lazy(() =>
  import("@/pages/employer/candidates/CandidatesPage").then((m) => ({
    default: m.CandidatesPage,
  })),
);
const CandidateDetailPage = lazy(() =>
  import("@/pages/employer/candidates/CandidateDetailPage").then((m) => ({
    default: m.CandidateDetailPage,
  })),
);
const SessionsPage = lazy(() =>
  import("@/pages/employer/sessions/SessionsPage").then((m) => ({
    default: m.SessionsPage,
  })),
);
const SessionDetailPage = lazy(() =>
  import("@/pages/employer/sessions/SessionDetailPage").then((m) => ({
    default: m.SessionDetailPage,
  })),
);
const BillingPage = lazy(() =>
  import("@/pages/employer/billing/BillingPage").then((m) => ({
    default: m.BillingPage,
  })),
);
const TeamPage = lazy(() =>
  import("@/pages/employer/team/TeamPage").then((m) => ({
    default: m.TeamPage,
  })),
);
const SettingsPage = lazy(() =>
  import("@/pages/employer/settings/SettingsPage").then((m) => ({
    default: m.SettingsPage,
  })),
);

// ── Candidate interview room (lazily loaded) ──────────────────────────────────
const CandidateRoomPage = lazy(() =>
  import("@/pages/candidate/CandidateRoomPage").then((m) => ({
    default: m.CandidateRoomPage,
  })),
);

// ── Not found ─────────────────────────────────────────────────────────────────
const NotFoundPage = lazy(() =>
  import("@/pages/NotFoundPage").then((m) => ({ default: m.NotFoundPage })),
);

// ── Suspense wrapper ──────────────────────────────────────────────────────────
function Lazy({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<LoadingPage />}>{children}</Suspense>
}

// ── Router ────────────────────────────────────────────────────────────────────
export const router = createBrowserRouter([
  // ── Public auth pages ────────────────────────────────────────────────────
  {
    element: <PublicLayout />,
    children: [
      { path: "/", element: <Navigate to="/login" replace /> },
      { path: "/onboarding", element: <OnboardingPage /> },
      { path: "/login", element: <LoginPage /> },
      { path: "/verify-email", element: <VerifyEmailPage /> },
      { path: "/forgot-password", element: <ForgotPasswordPage /> },
      { path: "/reset-password", element: <ResetPasswordPage /> },
    ],
  },

  // ── Employer portal (protected) ──────────────────────────────────────────
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppLayout />,
        children: [
          {
            path: "/app",
            element: <Navigate to="/app/dashboard" replace />,
          },
          {
            path: "/app/dashboard",
            element: <Lazy><DashboardPage /></Lazy>,
          },
          {
          path: "/app/admin",
    element: (
    <ProtectedRoute allowedRoles={["SUPER_ADMIN"]}>
      <Lazy><AdminPage /></Lazy>
    </ProtectedRoute>
),
},
          {
            path: "/app/jobs",
            element: <Lazy><JobsPage /></Lazy>,
          },
          {
            path: "/app/jobs/:jobId",
            element: <Lazy><JobDetailPage /></Lazy>,
          },
          {
            path: "/app/candidates",
            element: <Lazy><CandidatesPage /></Lazy>,
          },
          {
            path: "/app/candidates/:candidateId",
            element: <Lazy><CandidateDetailPage /></Lazy>,
          },
          {
            path: "/app/sessions",
            element: <Lazy><SessionsPage /></Lazy>,
          },
          {
            path: "/app/sessions/:sessionId",
            element: <Lazy><SessionDetailPage /></Lazy>,
          },
          {
            path: "/app/billing",
            element: <Lazy><BillingPage /></Lazy>,
          },
          {
            path: "/app/team",
            element: (
              <ProtectedRoute allowedRoles={["ADMIN"]}>
                <Lazy><TeamPage /></Lazy>
              </ProtectedRoute>
            ),
          },
          {
            path: "/app/settings",
            element: <Lazy><SettingsPage /></Lazy>,
          },
        ],
      },
    ],
  },

  // ── Candidate interview room ─────────────────────────────────────────────
  {
    element: <InterviewLayout />,
    children: [
      {
        path: "/interview/:sessionId",
        element: <Lazy><CandidateRoomPage /></Lazy>,
      },
    ],
  },

  // ── 404 ─────────────────────────────────────────────────────────────────
  {
    path: "*",
    element: <Lazy><NotFoundPage /></Lazy>,
  },
]);
