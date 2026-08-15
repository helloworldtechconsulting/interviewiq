// =============================================================================
// DashboardPage.tsx — Overview stats + recent sessions
// =============================================================================

import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  Briefcase,
  Users,
  Video,
  TrendingUp,
  Calendar,
  ArrowRight,
} from "lucide-react";
import type { ReactNode } from "react";

import { jobsApi } from "@/api/modules/jobs";
import { candidatesApi } from "@/api/modules/candidates";
import { sessionsApi } from "@/api/modules/sessions";
import { billingApi } from "@/api/modules/billing";
import { queryKeys } from "@/lib/queryKeys";
import { PageHeader } from "@/components/common/PageHeader";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Separator } from "@/components/ui/separator";
import { formatDateTime, formatRupees } from "@/lib/utils";
import { useAuthUser } from "@/stores/authStore";

// ── Stat card ─────────────────────────────────────────────────────────────────

interface StatCardProps {
  title: string;
  value: string | number;
  sub?: string;
  icon: ReactNode;
  loading?: boolean;
}

function StatCard({ title, value, sub, icon, loading }: StatCardProps) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {title}
        </CardTitle>
        <div className="text-muted-foreground">{icon}</div>
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="h-8 w-24 animate-pulse rounded bg-muted" />
        ) : (
          <p className="text-3xl font-bold">{value}</p>
        )}
        {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
      </CardContent>
    </Card>
  );
}

function getGreeting() {
  const h = new Date().getHours();
  if (h < 12) return "morning";
  if (h < 17) return "afternoon";
  return "evening";
}

// ── Component ─────────────────────────────────────────────────────────────────

export function DashboardPage() {
  const navigate = useNavigate();
  const user = useAuthUser();

  const { data: jobs, isLoading: jobsLoading } = useQuery({
    queryKey: queryKeys.jobs.list({ status: "OPEN" }),
    queryFn: () => jobsApi.list({ status: "OPEN", size: 1 }),
  });

  const { data: candidates, isLoading: candidatesLoading } = useQuery({
    queryKey: queryKeys.candidates.list(),
    queryFn: () => candidatesApi.list({ size: 1 }),
  });

  const { data: sessions, isLoading: sessionsLoading } = useQuery({
    queryKey: queryKeys.sessions.list({ page: 0 }),
    queryFn: () => sessionsApi.list({ size: 5 }),
  });

  const { data: wallet, isLoading: walletLoading } = useQuery({
    queryKey: queryKeys.billing.wallet(),
    queryFn: billingApi.getWallet,
  });

  const recentSessions = sessions?.content ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title={`Good ${getGreeting()}, ${user?.email?.split("@")[0] ?? "there"} 👋`}
        description="Here's what's happening across your hiring pipeline."
      />

      {/* ── Stats ──────────────────────────────────────────────────────────── */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="Open Jobs"
          value={jobs?.totalElements ?? 0}
          sub="Active job postings"
          icon={<Briefcase className="h-4 w-4" />}
          loading={jobsLoading}
        />
        <StatCard
          title="Total Candidates"
          value={candidates?.totalElements ?? 0}
          sub="Across all jobs"
          icon={<Users className="h-4 w-4" />}
          loading={candidatesLoading}
        />
        <StatCard
          title="Sessions Total"
          value={sessions?.totalElements ?? 0}
          sub="AI interview sessions"
          icon={<Video className="h-4 w-4" />}
          loading={sessionsLoading}
        />
        <StatCard
          title="Wallet Balance"
          value={wallet ? formatRupees(wallet.totalBalancePaise) : "—"}
          sub={
            wallet
              ? // Rs.100 per completed interview at every tier (§7.8.1), and the
                // promotional split is called out because free credit is spent
                // first — a customer must never be surprised by which money moved.
                `~${Math.floor(wallet.availablePaise / 10_000)} interviews left` +
                (wallet.promoBalancePaise > 0
                  ? ` · ${formatRupees(wallet.promoBalancePaise)} free credit`
                  : "")
              : "Loading…"
          }
          icon={<TrendingUp className="h-4 w-4" />}
          loading={walletLoading}
        />
      </div>

      {/* ── Recent sessions ─────────────────────────────────────────────────── */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2 text-base">
            <Calendar className="h-4 w-4" />
            Recent Sessions
          </CardTitle>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate("/app/sessions")}
            className="gap-1 text-xs"
          >
            View all <ArrowRight className="h-3 w-3" />
          </Button>
        </CardHeader>

        <Separator />

        <CardContent className="p-0">
          {sessionsLoading ? (
            <div className="space-y-3 p-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-14 animate-pulse rounded bg-muted" />
              ))}
            </div>
          ) : recentSessions.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Video className="mb-3 h-8 w-8 text-muted-foreground" />
              <p className="font-medium">No sessions yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Schedule your first AI interview to get started.
              </p>
              <Button
                className="mt-4"
                size="sm"
                onClick={() => navigate("/app/candidates")}
              >
                Go to Candidates
              </Button>
            </div>
          ) : (
            <ul className="divide-y">
              {recentSessions.map((s) => (
                <li
                  key={s.id}
                  className="flex cursor-pointer items-center gap-4 px-6 py-4 transition-colors hover:bg-muted/50"
                  onClick={() => navigate(`/app/sessions/${s.id}`)}
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
                    {s.candidateId.slice(0, 2).toUpperCase()}
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-mono text-sm font-medium">
                      {s.candidateId.slice(0, 8)}…
                    </p>
                    <p className="truncate text-xs text-muted-foreground">
                      {s.scheduledAt ? formatDateTime(s.scheduledAt) : "—"}
                    </p>
                  </div>
                  <StatusBadge kind="session" status={s.status} />
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* ── Quick actions ────────────────────────────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Quick Actions</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-3">
          <Button variant="outline" onClick={() => navigate("/app/jobs")}>
            <Briefcase className="mr-2 h-4 w-4" />
            Post a Job
          </Button>
          <Button variant="outline" onClick={() => navigate("/app/candidates")}>
            <Users className="mr-2 h-4 w-4" />
            Add Candidate
          </Button>
          <Button variant="outline" onClick={() => navigate("/app/billing")}>
            <TrendingUp className="mr-2 h-4 w-4" />
            Top Up Wallet
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
