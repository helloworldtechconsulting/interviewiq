// =============================================================================
// SessionDetailPage.tsx
//
// Shows session status, set meet URL, cancel, and (when COMPLETED) the full
// AI evaluation: radar chart, overall score, recommendation, strengths,
// improvements, and per-question breakdown.
// =============================================================================

import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import {
  RadarChart,
  Radar,
  PolarGrid,
  PolarAngleAxis,
  ResponsiveContainer,
} from "recharts";
import {
  ArrowLeft,
  Link2,
  XCircle,
  Loader2,
  CheckCircle2,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
  User,
} from "lucide-react";

import { sessionsApi } from "@/api/modules/sessions";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import { PageHeader } from "@/components/common/PageHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { EmptyState } from "@/components/common/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { cn, formatDateTime } from "@/lib/utils";
import type { EvaluationQuestion } from "@/types";

// ── Set meet URL schema ───────────────────────────────────────────────────────

const meetUrlSchema = z.object({
  meetUrl: z.string().url("Please enter a valid meeting URL"),
});
type MeetUrlForm = z.infer<typeof meetUrlSchema>;

// ── Recommendation badge ──────────────────────────────────────────────────────

function RecommendationBadge({
  value,
}: {
  value: "HIRE" | "HOLD" | "REJECT";
}) {
  const map = {
    HIRE: { label: "Hire", className: "bg-green-100 text-green-800" },
    HOLD: { label: "Hold", className: "bg-yellow-100 text-yellow-800" },
    REJECT: { label: "Reject", className: "bg-red-100 text-red-800" },
  } as const;
  const { label, className } = map[value];
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-3 py-1 text-sm font-semibold",
        className,
      )}
    >
      {label}
    </span>
  );
}

// ── Question card ─────────────────────────────────────────────────────────────

function QuestionCard({ q, index }: { q: EvaluationQuestion; index: number }) {
  const [open, setOpen] = useState(false);

  const difficultyColour: Record<string, string> = {
    EASY: "bg-green-100 text-green-700",
    MEDIUM: "bg-yellow-100 text-yellow-700",
    HARD: "bg-red-100 text-red-700",
  };

  return (
    <div className="rounded-lg border">
      <button
        type="button"
        className="flex w-full items-center justify-between px-4 py-3 text-left"
        onClick={() => setOpen((o) => !o)}
      >
        <div className="flex items-center gap-3">
          <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
            {index + 1}
          </span>
          <span className="text-sm font-medium">{q.text}</span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {q.score !== undefined && (
            <span className="text-sm font-semibold">{q.score}/10</span>
          )}
          <span
            className={cn(
              "rounded-full px-2 py-0.5 text-xs font-medium",
              difficultyColour[q.difficulty] ?? "bg-gray-100 text-gray-700",
            )}
          >
            {q.difficulty}
          </span>
          {open ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      </button>

      {open && (
        <div className="border-t px-4 py-3 space-y-2">
          {q.answer && (
            <div>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Candidate Answer
              </p>
              <p className="mt-1 text-sm">{q.answer}</p>
            </div>
          )}
          {q.feedback && (
            <div>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                AI Feedback
              </p>
              <p className="mt-1 text-sm text-muted-foreground">{q.feedback}</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Main Component ────────────────────────────────────────────────────────────

export function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();

  // ── Queries ────────────────────────────────────────────────────────────────

  const { data: session, isLoading, isError } = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId!),
    queryFn: () => sessionsApi.get(sessionId!),
    enabled: !!sessionId,
    // Poll every 10s while session is running so status updates live
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "INVITED" || status === "STARTED" ? 10_000 : false;
    },
  });

  const { data: evaluation } = useQuery({
    queryKey: queryKeys.sessions.evaluation(sessionId!),
    queryFn: () => sessionsApi.getEvaluation(sessionId!),
    enabled: session?.status === "COMPLETED",
    retry: false,
  });

  // ── Meet URL form ──────────────────────────────────────────────────────────

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<MeetUrlForm>({
    resolver: zodResolver(meetUrlSchema),
    defaultValues: { meetUrl: session?. meetUrl ?? "" },
  });

  const meetUrlMutation = useMutation({
    mutationFn: (data: MeetUrlForm) =>
      sessionsApi.setMeetUrl(sessionId!, { meetUrl: data.meetUrl }),
    onSuccess() {
      toast.success("Meeting URL saved.");
      void qc.invalidateQueries({
        queryKey: queryKeys.sessions.detail(sessionId!),
      });
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not save URL.",
      );
    },
  });

  const cancelMutation = useMutation({
    mutationFn: () => sessionsApi.cancel(sessionId!),
    onSuccess() {
      toast.success("Session cancelled.");
      void qc.invalidateQueries({ queryKey: queryKeys.sessions.all() });
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not cancel session.",
      );
    },
  });

  // ── Loading / error ────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-48 animate-pulse rounded bg-muted" />
        <div className="h-48 animate-pulse rounded-lg bg-muted" />
      </div>
    );
  }

  if (isError || !session) {
    return (
      <EmptyState
        title="Session not found"
        description="This session may have been deleted."
        action={{
          label: "Back to Sessions",
          onClick: () => navigate("/app/sessions"),
        }}
      />
    );
  }

  // ── Radar chart data ───────────────────────────────────────────────────────

  const radarData = evaluation?.scores
      ? [
        { subject: "Technical", score: evaluation.scores.technicalSkills ?? 0 },
        { subject: "Communication", score: evaluation.scores.communication ?? 0 },
        { subject: "Problem Solving", score: evaluation.scores.problemSolving ?? 0 },
        { subject: "Culture Fit", score: evaluation.scores.culturalFit ?? 0 },
      ]
      : [];

  const canCancel = session.status === "INVITED" || session.status === "STARTED";
  const canSetUrl = session.status === "INVITED";

  return (
    <div className="space-y-6">
      <PageHeader
        title={`Interview — ${session.candidateId.slice(0, 8)}…`}
        description={`Job ${session.jobOpeningId.slice(0, 8)}…`}
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate("/app/sessions")}
            >
              <ArrowLeft className="mr-1 h-4 w-4" />
              Back
            </Button>
            {canCancel && (
              <Button
                variant="destructive"
                size="sm"
                onClick={() => {
                  if (window.confirm("Cancel this session?")) {
                    cancelMutation.mutate();
                  }
                }}
                disabled={cancelMutation.isPending}
              >
                {cancelMutation.isPending ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <XCircle className="mr-1 h-4 w-4" />
                )}
                Cancel Session
              </Button>
            )}
          </div>
        }
      />

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ── Session meta ─────────────────────────────────────────────────── */}
        <div className="space-y-4 lg:col-span-1">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Session Info</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="space-y-3 pt-4">
              <div className="flex items-center gap-2">
                <StatusBadge kind="session" status={session.status} />
              </div>

              <dl className="space-y-2 text-sm">
                <div className="flex items-start justify-between gap-2">
                  <dt className="text-muted-foreground">Candidate</dt>
                  <dd
                    className="cursor-pointer font-mono text-xs font-medium hover:underline text-right"
                    onClick={() =>
                      navigate(`/app/candidates/${session.candidateId}`)
                    }
                  >
                    {session.candidateId.slice(0, 8)}…
                  </dd>
                </div>
                <div className="flex items-start justify-between gap-2">
                  <dt className="text-muted-foreground">Job</dt>
                  <dd
                    className="cursor-pointer font-mono text-xs font-medium hover:underline text-right"
                    onClick={() =>
                      navigate(`/app/jobs/${session.jobOpeningId}`)
                    }
                  >
                    {session.jobOpeningId.slice(0, 8)}…
                  </dd>
                </div>
                <div className="flex items-start justify-between gap-2">
                  <dt className="text-muted-foreground">Scheduled</dt>
                  <dd className="text-right font-medium">
                    {session.scheduledAt ? formatDateTime(session.scheduledAt) : "—"}
                  </dd>
                </div>
                {session.durationSeconds != null && (
                  <div className="flex items-start justify-between gap-2">
                    <dt className="text-muted-foreground">Duration</dt>
                    <dd className="font-medium">
                      {Math.round(session.durationSeconds / 60)} min
                    </dd>
                  </div>
                )}
              </dl>
            </CardContent>
          </Card>

          {/* Meet URL */}
          {canSetUrl && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Meeting Link</CardTitle>
              </CardHeader>
              <Separator />
              <CardContent className="pt-4">
                <p className="mb-3 text-sm text-muted-foreground">
                  Paste your Google Meet, Zoom, or Teams link. The AI bot will
                  join this URL at the scheduled time.
                </p>
                <form
                  onSubmit={handleSubmit((d) => meetUrlMutation.mutate(d))}
                  className="space-y-2"
                >
                  <Label htmlFor="meetUrl">Meeting URL</Label>
                  <Input
                    id="meetUrl"
                    type="url"
                    placeholder="https://meet.google.com/..."
                    defaultValue={session. meetUrl ?? ""}
                    {...register("meetUrl")}
                  />
                  {errors.meetUrl && (
                    <p className="text-xs text-destructive">
                      {errors.meetUrl.message}
                    </p>
                  )}
                  <Button
                    type="submit"
                    size="sm"
                    className="w-full"
                    disabled={meetUrlMutation.isPending}
                  >
                    {meetUrlMutation.isPending ? (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    ) : (
                      <Link2 className="mr-2 h-4 w-4" />
                    )}
                    Save URL
                  </Button>
                </form>
              </CardContent>
            </Card>
          )}

          {session. meetUrl && !canSetUrl && (
            <Card>
              <CardContent className="pt-4">
                <p className="text-xs text-muted-foreground">Meeting URL</p>
                <a
                  href={session. meetUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-1 flex items-center gap-1 break-all text-sm text-primary hover:underline"
                >
                  <Link2 className="h-3.5 w-3.5 shrink-0" />
                  {session. meetUrl}
                </a>
              </CardContent>
            </Card>
          )}
        </div>

        {/* ── Evaluation ────────────────────────────────────────────────────── */}
        <div className="space-y-4 lg:col-span-2">
          {session.status === "COMPLETED" && evaluation?.scores ? (
            <>
              {/* Overall + radar */}
              <Card>
                <CardHeader className="flex flex-row items-center justify-between">
                  <CardTitle className="text-base">AI Evaluation</CardTitle>
                  <RecommendationBadge value={evaluation.recommendation} />
                </CardHeader>
                <Separator />
                <CardContent className="pt-4">
                  <div className="grid gap-6 md:grid-cols-2">
                    {/* Radar chart */}
                    <div className="flex flex-col items-center">
                      <ResponsiveContainer width="100%" height={220}>
                        <RadarChart data={radarData}>
                          <PolarGrid />
                          <PolarAngleAxis
                            dataKey="subject"
                            tick={{ fontSize: 12 }}
                          />
                          <Radar
                            dataKey="score"
                            stroke="hsl(var(--primary))"
                            fill="hsl(var(--primary))"
                            fillOpacity={0.2}
                            dot
                          />
                        </RadarChart>
                      </ResponsiveContainer>
                    </div>

                    {/* Score breakdown */}
                    <div className="space-y-3">
                      <div className="flex items-center justify-between rounded-lg bg-primary/5 px-4 py-3">
                        <span className="font-semibold">Overall Score</span>
                        <span className="text-2xl font-bold text-primary">
                          {evaluation.scores.overallScore}
                          <span className="text-sm text-muted-foreground">/10</span>
                        </span>
                      </div>
                      {[
                        {
                          label: "Technical Skills",
                          v: evaluation.scores.technicalSkills,
                        },
                        {
                          label: "Communication",
                          v: evaluation.scores.communication,
                        },
                        {
                          label: "Problem Solving",
                          v: evaluation.scores.problemSolving,
                        },
                        {
                          label: "Cultural Fit",
                          v: evaluation.scores.culturalFit,
                        },
                      ].map(({ label, v }) => (
                        <div key={label}>
                          <div className="mb-1 flex justify-between text-sm">
                            <span className="text-muted-foreground">
                              {label}
                            </span>
                            <span className="font-medium">{v}/10</span>
                          </div>
                          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                            <div
                              className="h-full rounded-full bg-primary transition-all"
                              style={{ width: `${(v / 10) * 100}%` }}
                            />
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Summary */}
                  {evaluation.summary && (
                    <div className="mt-4 rounded-lg bg-muted/50 p-4">
                      <p className="text-sm">{evaluation.summary}</p>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* Strengths & improvements */}
              <div className="grid gap-4 md:grid-cols-2">
                <Card>
                  <CardHeader>
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <CheckCircle2 className="h-4 w-4 text-green-500" />
                      Strengths
                    </CardTitle>
                  </CardHeader>
                  <Separator />
                  <CardContent className="pt-3">
                    {evaluation.strengths.length === 0 ? (
                      <p className="text-sm text-muted-foreground">—</p>
                    ) : (
                      <ul className="space-y-1.5">
                        {evaluation.strengths.map((s, i) => (
                          <li
                            key={i}
                            className="flex gap-2 text-sm"
                          >
                            <span className="mt-0.5 h-1.5 w-1.5 shrink-0 rounded-full bg-green-500 mt-2" />
                            {s}
                          </li>
                        ))}
                      </ul>
                    )}
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <AlertTriangle className="h-4 w-4 text-yellow-500" />
                      Areas to Improve
                    </CardTitle>
                  </CardHeader>
                  <Separator />
                  <CardContent className="pt-3">
                    {evaluation.improvements.length === 0 ? (
                      <p className="text-sm text-muted-foreground">—</p>
                    ) : (
                      <ul className="space-y-1.5">
                        {evaluation.improvements.map((s, i) => (
                          <li key={i} className="flex gap-2 text-sm">
                            <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-yellow-500" />
                            {s}
                          </li>
                        ))}
                      </ul>
                    )}
                  </CardContent>
                </Card>
              </div>

              {/* Question breakdown */}
              {evaluation.questions.length > 0 && (
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">
                      Question Breakdown ({evaluation.questions.length})
                    </CardTitle>
                  </CardHeader>
                  <Separator />
                  <CardContent className="space-y-2 pt-4">
                    {evaluation.questions.map((q, i) => (
                      <QuestionCard key={q.id} q={q} index={i} />
                    ))}
                  </CardContent>
                </Card>
              )}

              {/* Transcript */}
              {evaluation.transcript && (
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">
                      Interview Transcript
                    </CardTitle>
                  </CardHeader>
                  <Separator />
                  <CardContent className="pt-4">
                    <pre className="max-h-96 overflow-y-auto whitespace-pre-wrap rounded-lg bg-muted/50 p-4 text-xs leading-relaxed">
                      {evaluation.transcript}
                    </pre>
                  </CardContent>
                </Card>
              )}
            </>
          ) : session.status === "COMPLETED" ? (
            <Card>
              <CardContent className="flex flex-col items-center justify-center py-12">
                <Loader2 className="mb-3 h-8 w-8 animate-spin text-primary" />
                <p className="font-medium">Generating evaluation…</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  The AI is analysing the interview. This may take a minute.
                </p>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="flex flex-col items-center justify-center py-16 text-center">
                <User className="mb-3 h-10 w-10 text-muted-foreground" />
                <p className="font-medium">
                  {session.status === "CANCELLED"
                    ? "Session was cancelled"
                    : session.status === "ERROR" || session.status === "EXPIRED"
                    ? "Session failed"
                    : "Evaluation not yet available"}
                </p>
                <p className="mt-1 max-w-xs text-sm text-muted-foreground">
                  {session.status === "INVITED"
                    ? "The evaluation report will appear here once the interview is completed."
                    : session.status === "STARTED"
                    ? "The interview is in progress. Check back once it completes."
                    : "No evaluation is available for this session."}
                </p>

                {/* Live polling indicator for in-progress */}
                {(session.status === "INVITED" || session.status === "STARTED") && (
                  <div className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-primary" />
                    Auto-refreshing every 10s
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
