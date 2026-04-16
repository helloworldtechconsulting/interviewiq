// =============================================================================
// SessionsPage.tsx — All interview sessions with status filter + pagination
// =============================================================================

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Video, ChevronLeft, ChevronRight, Search } from "lucide-react";

import { sessionsApi } from "@/api/modules/sessions";
import { queryKeys } from "@/lib/queryKeys";
import type { SessionStatus } from "@/types";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { formatDateTime } from "@/lib/utils";

const PAGE_SIZE = 10;

const STATUS_OPTIONS: { label: string; value: string }[] = [
  { label: "All statuses", value: "ALL" },
  { label: "Invited", value: "INVITED" },
  { label: "In Progress", value: "STARTED" },
  { label: "Completed", value: "COMPLETED" },
  { label: "Cancelled", value: "CANCELLED" },
  { label: "Expired", value: "EXPIRED" },
  { label: "Error", value: "ERROR" },
];

export function SessionsPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [search, setSearch] = useState("");

  const queryStatus =
    statusFilter === "ALL" ? undefined : (statusFilter as SessionStatus);

  const { data, isLoading } = useQuery({
    queryKey: queryKeys.sessions.list({ page, status: queryStatus }),
    queryFn: () =>
      sessionsApi.list({ page, size: PAGE_SIZE, status: queryStatus }),
  });

  const sessions = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const filtered = search.trim()
    ? sessions.filter(
        (s) =>
          s.candidateId.toLowerCase().includes(search.toLowerCase()) ||
          s.jobOpeningId.toLowerCase().includes(search.toLowerCase()),
      )
    : sessions;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Sessions"
        description="All AI interview sessions across your candidates."
      />

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search by candidate or job…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
        <Select
          value={statusFilter}
          onValueChange={(v) => {
            setStatusFilter(v);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-full sm:w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* List */}
      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-24 animate-pulse rounded-lg bg-muted" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Video}
          title="No sessions found"
          description={
            search
              ? "No sessions match your search."
              : "Schedule an AI interview from a candidate's profile."
          }
        />
      ) : (
        <div className="space-y-3">
          {filtered.map((s) => (
            <Card
              key={s.id}
              className="cursor-pointer transition-shadow hover:shadow-md"
              onClick={() => navigate(`/app/sessions/${s.id}`)}
            >
              <CardContent className="flex items-center gap-4 p-5">
                {/* Avatar */}
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
                  {s.candidateId.slice(0, 2).toUpperCase()}
                </div>

                {/* Details */}
                <div className="min-w-0 flex-1">
                  <p className="font-mono text-sm font-semibold">
                    {s.candidateId.slice(0, 8)}…
                  </p>
                  <p className="text-xs text-muted-foreground">
                    Job {s.jobOpeningId.slice(0, 8)}…
                  </p>
                  <p className="mt-0.5 text-xs text-muted-foreground">
                    {s.scheduledAt ? formatDateTime(s.scheduledAt) : "No schedule"}
                    {s.durationSeconds != null
                      ? ` · ${Math.round(s.durationSeconds / 60)} min`
                      : ""}
                  </p>
                </div>

                <div className="flex flex-col items-end gap-2">
                  <StatusBadge kind="session" status={s.status} />
                  {s.status === "COMPLETED" && (
                    <span className="text-xs font-medium text-green-600">
                      Evaluation ready
                    </span>
                  )}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            Page {page + 1} of {totalPages}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p - 1)}
              disabled={page === 0}
            >
              <ChevronLeft className="h-4 w-4" />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p + 1)}
              disabled={page >= totalPages - 1}
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
