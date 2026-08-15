// =============================================================================
// CandidateSchedulingPage.tsx — the candidate books their own interview
//
// PRD v2.1 §7.4.1. This page is what replaced employer-published availability
// windows, which v2.1 deletes entirely. The candidate opens their invite and
// sees two paths:
//
//   • "Start now"   — when questions are ready AND capacity is free
//   • "Pick a time" — any future time with capacity, 24x7
//
// THE READINESS GATE (§7.4.3) is the interesting part. The old design made every
// candidate wait 30 minutes after the invite. That buffer was always a proxy for
// "have the questions finished generating?", and generation takes about 20
// seconds — so the page now measures readiness directly and polls while it
// waits, showing "Preparing your interview — ready in about a minute".
//
// AVAILABILITY IS GENUINELY 24x7. If a slot is missing from the picker it is
// because the platform is at capacity for that moment, and for no other reason.
// There are no business hours and no quiet hours: "a candidate who wants to
// interview at 11pm on a Sunday should be able to. This is a selling point, not
// an oversight."
// =============================================================================

import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  CalendarDays,
  CheckCircle2,
  Clock,
  Loader2,
  PlayCircle,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";

import { schedulingApi } from "@/api/modules/scheduling";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";

/** How often to re-check readiness while questions generate. */
const READINESS_POLL_MS = 5_000;

/** Days offered in the picker, matching the booking horizon. */
const HORIZON_DAYS = 30;

function startOfDay(date: Date): Date {
  const copy = new Date(date);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function formatDayLabel(date: Date): string {
  const today = startOfDay(new Date());
  const target = startOfDay(date);
  const diffDays = Math.round((target.getTime() - today.getTime()) / 86_400_000);

  if (diffDays === 0) return "Today";
  if (diffDays === 1) return "Tomorrow";
  return date.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

export function CandidateSchedulingPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const token = searchParams.get("token");
  const [selectedDay, setSelectedDay] = useState<Date>(() => startOfDay(new Date()));
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);

  // ── Readiness ────────────────────────────────────────────────────────────
  //
  // Polls until questions are ready, then stops. This is the whole readiness
  // gate: no artificial wait, just a truthful "not yet".

  const { data: readiness, isLoading: readinessLoading } = useQuery({
    queryKey: ["scheduling", "readiness"],
    queryFn: schedulingApi.readiness,
    enabled: !!token,
    refetchInterval: (query) =>
      query.state.data?.questionsReady ? false : READINESS_POLL_MS,
  });

  // ── Available times for the selected day ─────────────────────────────────

  const dayRange = useMemo(() => {
    const from = new Date(selectedDay);
    const until = new Date(selectedDay);
    until.setHours(23, 59, 59, 999);
    return { from: from.toISOString(), until: until.toISOString() };
  }, [selectedDay]);

  const { data: times, isFetching: timesLoading } = useQuery({
    queryKey: ["scheduling", "times", dayRange.from],
    queryFn: () => schedulingApi.availableTimes(dayRange),
    enabled: !!token && !!readiness,
  });

  // ── Booking ──────────────────────────────────────────────────────────────

  const bookMutation = useMutation({
    mutationFn: (startAt: string) => schedulingApi.book(startAt),
    onSuccess(booking) {
      toast.success(
        `Booked for ${formatDayLabel(new Date(booking.scheduledStartAt))} at ${formatTime(
          booking.scheduledStartAt,
        )}. Check your email for the calendar invite.`,
      );
      void qc.invalidateQueries({ queryKey: ["scheduling"] });
    },
    onError(error) {
      // A ConflictException means someone took the last slot in a bucket between
      // the picker rendering and the candidate clicking. That is expected under
      // load, and the message tells them to pick again rather than failing
      // opaquely.
      toast.error(
        error instanceof AppError
          ? error.message
          : "That time is no longer available. Please choose another.",
      );
      void qc.invalidateQueries({ queryKey: ["scheduling", "times"] });
      setSelectedSlot(null);
    },
  });

  const startNow = () => navigate(`/interview/room?token=${token}`);

  // ── Guards ───────────────────────────────────────────────────────────────

  if (!token) {
    return (
      <Centered
        icon={<AlertTriangle className="h-12 w-12 text-destructive" />}
        title="This link is not valid"
        description="Please use the link from your interview invitation email."
      />
    );
  }

  if (readinessLoading || !readiness) {
    return (
      <Centered
        icon={<Loader2 className="h-12 w-12 animate-spin text-primary" />}
        title="Loading your interview…"
        description=""
      />
    );
  }

  const days = Array.from({ length: HORIZON_DAYS }, (_, i) => {
    const d = startOfDay(new Date());
    d.setDate(d.getDate() + i);
    return d;
  });

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <header className="space-y-2 text-center">
        <h1 className="text-3xl font-bold">When would you like to take your interview?</h1>
        <p className="text-muted-foreground">
          Your interview takes about {readiness.durationMinutes} minutes. You can start
          right away or pick a time that suits you — we&apos;re available around the clock.
        </p>
      </header>

      {/* ── Browser requirement, stated BEFORE they commit to a time ────────
          §17 rates "candidate arrives on Safari or Firefox" HIGH probability,
          mitigated by stating the requirement in the invite email and again
          here, before they book. */}
      <Card className="border-amber-200 bg-amber-50">
        <CardContent className="flex gap-3 pt-4 text-sm">
          <AlertTriangle className="h-5 w-5 shrink-0 text-amber-600" />
          <p className="text-amber-900">
            You&apos;ll need a <strong>Chromium-based desktop browser</strong> — Chrome,
            Edge, Brave or Arc — with a working camera and microphone. Safari and
            Firefox are not supported for the interview itself.
          </p>
        </CardContent>
      </Card>

      {/* ── Start now, or the readiness gate ─────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Start right away</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="pt-4">
          {!readiness.questionsReady ? (
            <div className="flex items-center gap-3">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              <div>
                <p className="font-medium">Preparing your interview</p>
                <p className="text-sm text-muted-foreground">
                  Ready in about a minute. You can pick a later time below if you prefer.
                </p>
              </div>
            </div>
          ) : readiness.canStartNow ? (
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <CheckCircle2 className="h-5 w-5 text-green-600" />
                <div>
                  <p className="font-medium">Your interview is ready</p>
                  <p className="text-sm text-muted-foreground">
                    Make sure you&apos;re somewhere quiet with a good connection.
                  </p>
                </div>
              </div>
              <Button onClick={startNow} size="lg">
                <PlayCircle className="mr-2 h-4 w-4" />
                Start now
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <Clock className="h-5 w-5 text-muted-foreground" />
              <div>
                <p className="font-medium">We&apos;re at capacity right now</p>
                <p className="text-sm text-muted-foreground">
                  {readiness.earliestBookableAt
                    ? `The earliest we can take you is ${formatDayLabel(
                        new Date(readiness.earliestBookableAt),
                      )} at ${formatTime(readiness.earliestBookableAt)}.`
                    : "Please try a later date below."}
                </p>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* ── Pick a time ──────────────────────────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <CalendarDays className="h-4 w-4" />
            Or pick a time
          </CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="space-y-4 pt-4">
          {/* Day strip */}
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="icon"
              aria-label="Previous days"
              onClick={() => {
                const d = new Date(selectedDay);
                d.setDate(d.getDate() - 1);
                if (d >= startOfDay(new Date())) setSelectedDay(d);
              }}
              disabled={selectedDay <= startOfDay(new Date())}
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>

            <div className="flex flex-1 gap-2 overflow-x-auto pb-1">
              {days.slice(0, 14).map((day) => (
                <button
                  key={day.toISOString()}
                  type="button"
                  onClick={() => {
                    setSelectedDay(day);
                    setSelectedSlot(null);
                  }}
                  className={cn(
                    "shrink-0 rounded-md border px-3 py-2 text-sm transition-colors",
                    startOfDay(day).getTime() === selectedDay.getTime()
                      ? "border-primary bg-primary text-primary-foreground"
                      : "hover:bg-muted",
                  )}
                >
                  {formatDayLabel(day)}
                </button>
              ))}
            </div>

            <Button
              variant="outline"
              size="icon"
              aria-label="Next days"
              onClick={() => {
                const d = new Date(selectedDay);
                d.setDate(d.getDate() + 1);
                setSelectedDay(d);
                setSelectedSlot(null);
              }}
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>

          {/* Slots */}
          {timesLoading ? (
            <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              Checking availability…
            </div>
          ) : !times || times.availableStartTimes.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">
              {/* §7.4.2 accepts this outcome explicitly: because capacity is
                  enforced, "the failure mode is 'no time available', not a
                  degraded interview". */}
              <p>No times available on this day.</p>
              <p className="mt-1">Please try another date.</p>
            </div>
          ) : (
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-6">
              {times.availableStartTimes.map((slot) => (
                <button
                  key={slot}
                  type="button"
                  onClick={() => setSelectedSlot(slot)}
                  className={cn(
                    "rounded-md border px-2 py-2 text-sm transition-colors",
                    selectedSlot === slot
                      ? "border-primary bg-primary text-primary-foreground"
                      : "hover:bg-muted",
                  )}
                >
                  {formatTime(slot)}
                </button>
              ))}
            </div>
          )}

          {selectedSlot && (
            <div className="flex items-center justify-between rounded-md bg-muted p-3">
              <p className="text-sm">
                <strong>{formatDayLabel(new Date(selectedSlot))}</strong> at{" "}
                <strong>{formatTime(selectedSlot)}</strong> ·{" "}
                {readiness.durationMinutes} minutes
              </p>
              <Button
                onClick={() => bookMutation.mutate(selectedSlot)}
                disabled={bookMutation.isPending}
              >
                {bookMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Confirm booking
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <p className="text-center text-xs text-muted-foreground">
        You can reschedule any time before your interview starts.
      </p>
    </div>
  );
}

function Centered({
  icon,
  title,
  description,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 p-8 text-center">
      {icon}
      <div>
        <h2 className="text-2xl font-bold">{title}</h2>
        {description && <p className="mt-2 text-muted-foreground">{description}</p>}
      </div>
    </div>
  );
}
