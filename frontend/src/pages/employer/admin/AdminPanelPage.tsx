// =============================================================================
// AdminPanelPage.tsx — platform-staff console (INTIQ-35)
//
// The only screen in the product that shows data across tenants. Two things
// follow from that and neither is optional:
//
//   • The route is hidden from anyone without PLATFORM_STAFF, and the API
//     refuses them regardless. The hiding is convenience; the `@PreAuthorize`
//     on the controller is the actual control.
//
//   • Manual credit is the one action here that creates spendable money in a
//     customer's account. The reason field is required at 10 characters by the
//     server, and this form says why rather than just enforcing it — someone
//     typing "fix" should understand what they are making harder for the person
//     who reads this in six months.
// =============================================================================

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Building2, Coins, RotateCcw, TrendingUp } from "lucide-react";

import { adminApi, type AdminCompanyRow } from "@/api/modules/admin";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { PageHeader } from "@/components/common/PageHeader";
import { formatRupees } from "@/lib/utils";

/** Reasons rendered in plain language — the enum name is not for reading. */
const RETIREMENT_REASONS: Record<string, string> = {
  HIGH_SKIP_RATE: "Most candidates skipped it",
  SHORT_ANSWERS: "Drew very short answers",
  NO_SCORE_VARIANCE: "Everyone scored the same — no signal",
  CANDIDATE_FLAGGED: "Flagged by candidates",
  MANUAL: "Removed by staff",
};

export function AdminPanelPage() {
  const [page, setPage] = useState(0);

  const { data: stats } = useQuery({
    queryKey: ["admin", "stats"],
    queryFn: adminApi.platformStats,
  });

  const { data: companies, isLoading } = useQuery({
    queryKey: ["admin", "companies", page],
    queryFn: () => adminApi.listCompanies({ page, size: 25 }),
    placeholderData: (previous) => previous,
  });

  return (
    <div className="space-y-6">
      <PageHeader
        title="Platform console"
        description="Every company on the platform. Staff only."
      />

      {/* ── Platform totals ─────────────────────────────────────────────── */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Metric
          label="Active companies"
          value={stats?.activeCompanies ?? 0}
          icon={<Building2 className="h-4 w-4" />}
        />
        <Metric
          label="Interviews completed"
          value={stats?.interviewsCompleted ?? 0}
          sub={`${stats?.interviewsPending ?? 0} in flight`}
          icon={<TrendingUp className="h-4 w-4" />}
        />
        <Metric
          label="Gross revenue"
          value={stats ? formatRupees(stats.grossRevenuePaise) : "—"}
          sub="Settled interviews"
          icon={<Coins className="h-4 w-4" />}
        />
        {/* Outstanding promo is a liability, not a vanity number — the signup
            grant is capped against it (§7.8.3), so it belongs on this row. */}
        <Metric
          label="Promo exposure"
          value={stats ? formatRupees(stats.outstandingPromoPaise) : "—"}
          sub={
            stats
              ? `${formatRupees(stats.outstandingReservationsPaise)} reserved`
              : undefined
          }
          icon={<Coins className="h-4 w-4" />}
        />
      </div>

      <ManualCreditPanel companies={companies?.content ?? []} />

      {/* ── Companies ───────────────────────────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Companies</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="p-0">
          {isLoading ? (
            <div className="space-y-2 p-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-12 animate-pulse rounded bg-muted" />
              ))}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="p-3">Company</th>
                    <th className="p-3 text-right">Done</th>
                    <th className="p-3 text-right">In flight</th>
                    <th className="p-3 text-right">Balance</th>
                    <th className="p-3 text-right">Free credit</th>
                    <th className="p-3 text-right">Lifetime spend</th>
                  </tr>
                </thead>
                <tbody>
                  {(companies?.content ?? []).map((c) => (
                    <tr key={c.companyId} className="border-b last:border-0">
                      <td className="p-3">
                        <div className="font-medium">{c.name}</div>
                        <div className="text-xs text-muted-foreground">{c.slug}</div>
                      </td>
                      <td className="p-3 text-right">{c.interviewsCompleted}</td>
                      <td className="p-3 text-right">{c.interviewsPending}</td>
                      <td className="p-3 text-right">{formatRupees(c.balancePaise)}</td>
                      <td className="p-3 text-right text-muted-foreground">
                        {c.promoBalancePaise > 0 ? formatRupees(c.promoBalancePaise) : "—"}
                      </td>
                      <td className="p-3 text-right">{formatRupees(c.lifetimeSpendPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {companies && companies.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {companies.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= companies.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      )}

      <RetiredQuestionsPanel />
    </div>
  );
}

// =============================================================================
// Manual credit
// =============================================================================

function ManualCreditPanel({ companies }: { companies: AdminCompanyRow[] }) {
  const qc = useQueryClient();
  const [companyId, setCompanyId] = useState("");
  const [rupees, setRupees] = useState("");
  const [reason, setReason] = useState("");

  const mutation = useMutation({
    mutationFn: () =>
      adminApi.manualCredit({
        companyId,
        amountPaise: Math.round(Number(rupees) * 100),
        reason: reason.trim(),
      }),
    onSuccess() {
      toast.success("Credit applied.");
      setRupees("");
      setReason("");
      void qc.invalidateQueries({ queryKey: ["admin"] });
    },
    onError(error) {
      toast.error(error instanceof AppError ? error.message : "Could not apply the credit.");
    },
  });

  const amountValid = Number(rupees) > 0;
  const reasonValid = reason.trim().length >= 10;
  const ready = companyId !== "" && amountValid && reasonValid;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Manual credit</CardTitle>
      </CardHeader>
      <Separator />
      <CardContent className="space-y-4 pt-4">
        <p className="text-sm text-muted-foreground">
          Adds <strong>paid</strong> balance — for a refund, a goodwill gesture, or a
          payment that arrived outside Razorpay. It does not expire and is not
          promotional credit. Use promotional credit for trials instead.
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label className="text-sm">Company</Label>
            <select
              value={companyId}
              onChange={(e) => setCompanyId(e.target.value)}
              className="w-full rounded-md border bg-background px-3 py-2 text-sm"
            >
              <option value="">— choose —</option>
              {companies.map((c) => (
                <option key={c.companyId} value={c.companyId}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label className="text-sm">Amount (₹)</Label>
            <Input
              type="number"
              min={1}
              value={rupees}
              onChange={(e) => setRupees(e.target.value)}
              placeholder="500"
            />
          </div>
        </div>

        <div className="space-y-1.5">
          <Label className="text-sm">Reason</Label>
          <Input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Refund for interview that failed on our side, ticket 412"
          />
          {/* Says why rather than only enforcing it. Whoever reads this row in
              six months will not have the context that makes "fix" meaningful. */}
          <p className="text-xs text-muted-foreground">
            Recorded on the transaction and in the audit log. Write what someone with
            no memory of today would need — a ticket number beats an adjective.
            {reason.length > 0 && !reasonValid && (
              <span className="ml-1 text-destructive">
                At least 10 characters.
              </span>
            )}
          </p>
        </div>

        <div className="flex justify-end">
          <Button onClick={() => mutation.mutate()} disabled={!ready || mutation.isPending}>
            Apply credit
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

// =============================================================================
// Retired questions (INTIQ-93 oversight)
// =============================================================================

function RetiredQuestionsPanel() {
  const qc = useQueryClient();

  const { data: retired = [] } = useQuery({
    queryKey: ["admin", "retired-questions"],
    queryFn: adminApi.retiredQuestions,
  });

  const { data: preview } = useQuery({
    queryKey: ["admin", "retirement-preview"],
    queryFn: adminApi.retirementPreview,
  });

  const reinstate = useMutation({
    mutationFn: (id: string) => adminApi.reinstateQuestion(id),
    onSuccess() {
      toast.success("Question put back into rotation.");
      void qc.invalidateQueries({ queryKey: ["admin", "retired-questions"] });
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Retired questions</CardTitle>
      </CardHeader>
      <Separator />
      <CardContent className="space-y-4 pt-4">
        <p className="text-sm text-muted-foreground">
          Questions the automatic rules removed from rotation. Recruiters never see
          these — this is the only place the decisions are visible, and the only place
          they can be reversed.
          {preview != null && (
            <>
              {" "}
              At the current thresholds, <strong>{preview.wouldRetire}</strong> more
              would be retired on the next sweep.
            </>
          )}
        </p>

        {retired.length === 0 ? (
          <p className="py-4 text-sm text-muted-foreground">
            Nothing retired yet. Questions need a minimum number of answers before any
            rule applies to them.
          </p>
        ) : (
          <div className="space-y-2">
            {retired.map((q) => (
              <div key={q.id} className="rounded-md border p-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-sm">{q.questionText}</p>
                    <p className="mt-1 text-xs text-destructive">
                      {RETIREMENT_REASONS[q.retiredReason] ?? q.retiredReason}
                    </p>
                    {/* The numbers that triggered it, so the decision can be
                        judged rather than taken on trust. */}
                    <p className="mt-1 text-xs text-muted-foreground">
                      asked {q.timesAsked} · skipped {q.timesSkipped} · short{" "}
                      {q.shortAnswers} · scored {q.scoredCount} · mean{" "}
                      {q.scoreMean.toFixed(1)} · flags {q.candidateFlags}
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    className="shrink-0 gap-1"
                    onClick={() => reinstate.mutate(q.id)}
                    disabled={reinstate.isPending}
                  >
                    <RotateCcw className="h-3 w-3" />
                    Put back
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// =============================================================================

function Metric({
  label,
  value,
  sub,
  icon,
}: {
  label: string;
  value: number | string;
  sub?: string;
  icon: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="pt-6">
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">{label}</p>
          <span className="text-muted-foreground">{icon}</span>
        </div>
        <p className="mt-2 text-2xl font-bold">{value}</p>
        {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
      </CardContent>
    </Card>
  );
}
