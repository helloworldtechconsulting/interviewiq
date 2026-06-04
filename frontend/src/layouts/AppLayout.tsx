// =============================================================================
// AppLayout.tsx — Employer portal shell
//
// ┌──────────────────────────────────────┐
// │  Sidebar  │  Header  (top bar)       │
// │           │─────────────────────────│
// │  Nav      │  <Outlet />             │
// │  links    │  (page content)         │
// └──────────────────────────────────────┘
// =============================================================================

import { NavLink, Outlet, useNavigate } from "react-router-dom";
import {
  BrainCircuit,
  LayoutDashboard,
  Briefcase,
  Users,
  Video,
  CreditCard,
  Settings,
  UsersRound,
  ShieldCheck,
  LogOut,
  Menu,
  X,
} from "lucide-react";
import { cn, initials } from "@/lib/utils";
import { authStore, useAuthUser } from "@/stores/authStore";
import { useUiStore } from "@/stores/uiStore";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { authApi } from "@/api/modules/auth";
import { queryClient } from "@/lib/queryClient";
import { useQuery } from "@tanstack/react-query";
import { billingApi } from "@/api/modules/billing";
import { queryKeys } from "@/lib/queryKeys";
import { formatRupees } from "@/lib/utils";

export function AppLayout() {
  const { data: wallet } = useQuery({
    queryKey: queryKeys.billing.wallet(),
    queryFn: billingApi.getWallet,
  });
  const user = useAuthUser();
  const navItems = [
    { to: "/app/dashboard", icon: LayoutDashboard, label: "Dashboard" },
    { to: "/app/jobs", icon: Briefcase, label: "Jobs" },
    { to: "/app/candidates", icon: Users, label: "Candidates" },
    { to: "/app/sessions", icon: Video, label: "Sessions" },
    { to: "/app/billing", icon: CreditCard, label: "Billing" },
    ...(user?.role === "SUPER_ADMIN"
        ? [{ to: "/app/admin", icon: ShieldCheck, label: "Admin" }]
        : []),
    { to: "/app/team", icon: UsersRound, label: "Team" },
    { to: "/app/settings", icon: Settings, label: "Settings" },
  ];

  const { sidebarOpen, toggleSidebar } = useUiStore();
  const navigate = useNavigate();

  async function handleLogout() {
    try {
      await authApi.logout();
    } catch {
      // Best-effort logout — always clear local state
    }
    authStore.getState().logout();
    queryClient.clear();
    navigate("/login", { replace: true });
  }

  return (
    <div className="flex min-h-screen">
      {/* ── Sidebar ─────────────────────────────────────────────────────────── */}
      <aside
        className={cn(
          "flex flex-col border-r bg-card transition-all duration-200",
          sidebarOpen ? "w-56" : "w-16",
        )}
      >
        {/* Brand */}
        <div className="flex h-14 items-center gap-2 px-4">
          <BrainCircuit className="h-6 w-6 shrink-0 text-primary" />
          {sidebarOpen && (
            <span className="text-base font-bold tracking-tight">
              InterviewIQ
            </span>
          )}
        </div>

        <Separator />

        {/* Nav links */}
        <nav className="flex flex-1 flex-col gap-1 p-2">
          {navItems.map(({ to, icon: Icon, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                  isActive
                    ? "bg-primary/10 text-primary"
                    : "text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                  !sidebarOpen && "justify-center px-2",
                )
              }
              title={!sidebarOpen ? label : undefined}
            >
              <Icon className="h-4 w-4 shrink-0" />
              {sidebarOpen && <span>{label}</span>}
            </NavLink>
          ))}
        </nav>

        <Separator />

        {/* User info + logout */}
        <div className="p-2">
          {sidebarOpen ? (
            <div className="flex items-center gap-2 rounded-md px-3 py-2">
              <Avatar className="h-7 w-7">
                <AvatarFallback className="text-xs">
                  {user ? initials(user.email) : "?"}
                </AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-medium">{user?.email}</p>
                <p className="text-xs capitalize text-muted-foreground">
                  {user?.role?.toLowerCase()}
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 shrink-0"
                onClick={handleLogout}
                title="Log out"
              >
                <LogOut className="h-3.5 w-3.5" />
              </Button>
            </div>
          ) : (
            <Button
              variant="ghost"
              size="icon"
              className="w-full"
              onClick={handleLogout}
              title="Log out"
            >
              <LogOut className="h-4 w-4" />
            </Button>
          )}
        </div>
      </aside>

      {/* ── Main column ─────────────────────────────────────────────────────── */}
      <div className="flex flex-1 flex-col overflow-hidden">
        {/* Top bar */}
        <header className="flex h-14 shrink-0 items-center gap-3 border-b bg-card px-4">
          <Button
            variant="ghost"
            size="icon"
            onClick={toggleSidebar}
            aria-label="Toggle sidebar"
          >
            {sidebarOpen ? (
              <X className="h-4 w-4" />
            ) : (
              <Menu className="h-4 w-4" />
            )}
          </Button>
          {/* Breadcrumbs or page title injected via context in each page */}
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6">

          {/* ── Low balance banner ── */}
          {wallet && wallet.balancePaise <= 30000 && (
              <div className="mb-4 flex items-center justify-between rounded-lg border border-yellow-200 bg-yellow-50 px-4 py-3">
                <div className="flex items-center gap-2">
                  <span className="text-yellow-600">⚠️</span>
                  <p className="text-sm font-medium text-yellow-800">
                    Your wallet balance is low ({formatRupees(wallet.balancePaise)}).
                    Please top up to continue scheduling interviews.
                  </p>
                </div>
                <Button
                    size="sm"
                    variant="outline"
                    className="border-yellow-400 text-yellow-800 hover:bg-yellow-100"
                    onClick={() => navigate("/app/billing")}
                >
                  Top Up
                </Button>
              </div>
          )}

          <Outlet />
        </main>
      </div>
    </div>
  );
}
