// =============================================================================
// CandidateRoomPage.tsx — Candidate-facing interview portal
//
// URL: /interview/:sessionId?token=<invite-jwt>
//
// Auth: invite token read from URL ?token= by candidateClient (no Zustand).
//
// State machine:
//   WAITING        — session not started yet, show countdown + meeting link
//   IN_PROGRESS    — bot is in the call; show live status
//   ANSWERING      — candidate submits written answers (question by question)
//   SUBMITTED      — all answers submitted
//   COMPLETED      — interview done, evaluation generated
//   CANCELLED/FAILED — show graceful error state
//
// The page polls the session every 10 s to detect status transitions.
// =============================================================================

import { useEffect, useReducer, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { useQuery, useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  Video,
  Mic,
  CheckCircle2,
  Clock,
  AlertTriangle,
  ChevronRight,
  ExternalLink,
  Loader2,
  Send,
} from "lucide-react";

import { candidateRoomApi } from "@/api/modules/candidate";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { cn, formatDateTime } from "@/lib/utils";
import type { CandidateQuestion } from "@/types";

// ── Room state machine ────────────────────────────────────────────────────────

type RoomPhase =
  | "LOADING"
  | "WAITING"       // session scheduled, not started
  | "IN_PROGRESS"   // bot joined / interview live in video call
  | "ANSWERING"     // candidate answering questions one by one
  | "SUBMITTED"     // all answers sent, waiting for AI evaluation
  | "COMPLETED"     // evaluation done
  | "CANCELLED"
  | "FAILED"
  | "INVALID_TOKEN"; // no ?token= in URL

interface RoomState {
  phase: RoomPhase;
  currentQuestionIndex: number;
  answers: Record<string, string>; // questionId → answer text
}

type RoomAction =
  | { type: "SET_PHASE"; phase: RoomPhase }
  | { type: "NEXT_QUESTION" }
  | { type: "SET_ANSWER"; questionId: string; text: string }
  | { type: "SUBMIT_COMPLETE" };

function reducer(state: RoomState, action: RoomAction): RoomState {
  switch (action.type) {
    case "SET_PHASE":
      return { ...state, phase: action.phase };
    case "NEXT_QUESTION":
      return {
        ...state,
        currentQuestionIndex: state.currentQuestionIndex + 1,
      };
    case "SET_ANSWER":
      return {
        ...state,
        answers: { ...state.answers, [action.questionId]: action.text },
      };
    case "SUBMIT_COMPLETE":
      return { ...state, phase: "SUBMITTED" };
    default:
      return state;
  }
}

// ── Countdown timer ───────────────────────────────────────────────────────────

function useCountdown(targetIso: string | undefined) {
  const [diff, setDiff] = useState<number>(0);

  useEffect(() => {
    if (!targetIso) return;
    const target = new Date(targetIso).getTime();
    const tick = () => setDiff(Math.max(0, target - Date.now()));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [targetIso]);

  const totalSeconds = Math.floor(diff / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n: number) => String(n).padStart(2, "0");

  return {
    display:
      hours > 0
        ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
        : `${pad(minutes)}:${pad(seconds)}`,
    isPast: diff === 0,
    totalSeconds,
  };
}

// ── Progress bar ──────────────────────────────────────────────────────────────

function ProgressBar({
  current,
  total,
}: {
  current: number;
  total: number;
}) {
  const pct = total > 0 ? Math.round((current / total) * 100) : 0;
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>Question {current} of {total}</span>
        <span>{pct}%</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className="h-full bg-primary transition-all duration-300"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

// ── Difficulty badge ──────────────────────────────────────────────────────────

function DifficultyBadge({ level }: { level: string }) {
  const colours: Record<string, string> = {
    EASY: "bg-green-100 text-green-700",
    MEDIUM: "bg-yellow-100 text-yellow-700",
    HARD: "bg-red-100 text-red-700",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
        colours[level] ?? "bg-gray-100 text-gray-700",
      )}
    >
      {level.charAt(0) + level.slice(1).toLowerCase()}
    </span>
  );
}

// ── Phase screens ─────────────────────────────────────────────────────────────

function WaitingScreen({
  sessionData,
  onStartAnswering,
}: {
  sessionData: import("@/types").Session;
  onStartAnswering: () => void;
}) {
  const countdown = useCountdown(sessionData.scheduledAt ?? undefined);

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-primary/10 p-6">
        <Clock className="h-12 w-12 text-primary" />
      </div>

      <div>
        <h2 className="text-2xl font-bold">Your interview starts soon</h2>
        <p className="mt-2 text-muted-foreground">
          InterviewIQ AI Interview
        </p>
      </div>

      {sessionData.scheduledAt && !countdown.isPast ? (
        <div className="rounded-xl bg-muted px-8 py-4">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Starts in
          </p>
          <p className="mt-1 font-mono text-4xl font-bold tabular-nums">
            {countdown.display}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {formatDateTime(sessionData.scheduledAt)}
          </p>
        </div>
      ) : (
        <div className="rounded-xl border border-green-200 bg-green-50 px-8 py-4">
          <p className="font-semibold text-green-700">Interview time!</p>
          <p className="mt-1 text-sm text-green-600">
            Please join the meeting link below and wait for the AI bot to join.
          </p>
        </div>
      )}

      {sessionData.googleMeetUrl && (
        <a
          href={sessionData.googleMeetUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-lg bg-primary px-6 py-3 font-semibold text-primary-foreground transition-opacity hover:opacity-90"
        >
          <Video className="h-5 w-5" />
          Join Meeting
          <ExternalLink className="h-4 w-4" />
        </a>
      )}

      <div className="max-w-md rounded-lg bg-muted/50 p-4 text-left text-sm text-muted-foreground">
        <p className="mb-2 font-semibold text-foreground">What to expect:</p>
        <ol className="list-inside list-decimal space-y-1">
          <li>Join the video call at the scheduled time</li>
          <li>An AI interviewer will join and introduce itself</li>
          <li>Answer questions naturally — speak clearly</li>
          <li>
            After the call, return here to submit written answers if needed
          </li>
        </ol>
      </div>

      {/* Allow candidate to start answering questions early */}
      <Button variant="outline" onClick={onStartAnswering}>
        Answer Written Questions Early
        <ChevronRight className="ml-1 h-4 w-4" />
      </Button>
    </div>
  );
}

function InProgressScreen({ meetUrl }: { meetUrl?: string }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="relative rounded-full bg-primary/10 p-6">
        <Mic className="h-12 w-12 text-primary" />
        <span className="absolute right-1 top-1 flex h-4 w-4">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-75" />
          <span className="relative inline-flex h-4 w-4 rounded-full bg-primary" />
        </span>
      </div>
      <div>
        <h2 className="text-2xl font-bold">Interview in progress</h2>
        <p className="mt-2 text-muted-foreground">
          The AI interviewer is active. Please stay in the meeting.
        </p>
      </div>
      {meetUrl && (
        <a
          href={meetUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-lg bg-primary px-6 py-3 font-semibold text-primary-foreground hover:opacity-90"
        >
          <Video className="h-5 w-5" />
          Rejoin Meeting
          <ExternalLink className="h-4 w-4" />
        </a>
      )}
      <p className="text-sm text-muted-foreground">
        This page auto-refreshes. Written questions will appear here after the
        call ends.
      </p>
    </div>
  );
}

function CompletedScreen({ candidateName }: { candidateName: string }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-green-100 p-6">
        <CheckCircle2 className="h-12 w-12 text-green-600" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">
          Great job, {candidateName.split(" ")[0]}!
        </h2>
        <p className="mt-2 text-muted-foreground">
          Your interview is complete. The hiring team will review your responses
          and be in touch soon.
        </p>
      </div>
      <div className="max-w-sm rounded-lg bg-muted/50 p-4 text-sm text-muted-foreground">
        You can safely close this tab. No further action is required.
      </div>
    </div>
  );
}

function SubmittedScreen() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-primary/10 p-6">
        <Loader2 className="h-12 w-12 animate-spin text-primary" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">Answers submitted!</h2>
        <p className="mt-2 text-muted-foreground">
          The AI is evaluating your responses. You'll be notified when results
          are ready.
        </p>
      </div>
    </div>
  );
}

function ErrorScreen({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-destructive/10 p-6">
        <AlertTriangle className="h-12 w-12 text-destructive" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">{title}</h2>
        <p className="mt-2 text-muted-foreground">{description}</p>
      </div>
    </div>
  );
}

// ── Question answering screen ──────────────────────────────────────────────────

interface AnsweringScreenProps {
  questions: CandidateQuestion[];
  state: RoomState;
  dispatch: React.Dispatch<RoomAction>;
  sessionId: string;
  candidateName: string;
}

function AnsweringScreen({
  questions,
  state,
  dispatch,
  sessionId,
  candidateName,
}: AnsweringScreenProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const currentQ = questions[state.currentQuestionIndex];
  const isLast = state.currentQuestionIndex === questions.length - 1;
  const currentAnswer = currentQ
    ? (state.answers[currentQ.id] ?? "")
    : "";

  const submitMutation = useMutation({
    mutationFn: ({
      questionId,
      answer,
    }: {
      questionId: string;
      answer: string;
    }) => candidateRoomApi.submitAnswer(sessionId, { questionId, answer }),
    onSuccess() {
      if (isLast) {
        dispatch({ type: "SUBMIT_COMPLETE" });
        toast.success("All answers submitted! Thank you.");
      } else {
        dispatch({ type: "NEXT_QUESTION" });
        // Focus textarea for next question
        setTimeout(() => textareaRef.current?.focus(), 100);
      }
    },
    onError(error) {
      toast.error(
        error instanceof AppError
          ? error.message
          : "Failed to submit answer. Please try again.",
      );
    },
  });

  function handleSubmitAnswer() {
    if (!currentQ) return;
    if (!currentAnswer.trim()) {
      toast.error("Please write an answer before continuing.");
      return;
    }
    submitMutation.mutate({
      questionId: currentQ.id,
      answer: currentAnswer.trim(),
    });
  }

  if (!currentQ) return null;

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 p-6">
      {/* Header */}
      <div className="text-center">
        <p className="text-sm text-muted-foreground">
          AI Interview
        </p>
        <ProgressBar
          current={state.currentQuestionIndex + 1}
          total={questions.length}
        />
      </div>

      {/* Question card */}
      <Card className="flex-1">
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
              {state.currentQuestionIndex + 1}
            </span>
            <span className="text-xs text-muted-foreground">{currentQ.topic}</span>
          </div>
          <DifficultyBadge level={currentQ.difficulty} />
        </CardHeader>
        <Separator />
        <CardContent className="flex flex-col gap-4 pt-5">
          <p className="text-lg font-medium leading-relaxed">{currentQ.text}</p>

          <div className="space-y-1.5">
            <label
              htmlFor="answer"
              className="text-sm font-medium text-muted-foreground"
            >
              Your answer
            </label>
            <textarea
              id="answer"
              ref={textareaRef}
              rows={7}
              placeholder="Type your answer here…"
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 resize-none"
              value={currentAnswer}
              onChange={(e) =>
                dispatch({
                  type: "SET_ANSWER",
                  questionId: currentQ.id,
                  text: e.target.value,
                })
              }
              disabled={submitMutation.isPending}
              autoFocus
            />
            <p className="text-right text-xs text-muted-foreground">
              {currentAnswer.length} characters
            </p>
          </div>
        </CardContent>
      </Card>

      {/* Actions */}
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Take your time — there's no time limit per question.
        </p>
        <Button
          onClick={handleSubmitAnswer}
          disabled={submitMutation.isPending || !currentAnswer.trim()}
          className="min-w-32"
        >
          {submitMutation.isPending ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          ) : isLast ? (
            <Send className="mr-2 h-4 w-4" />
          ) : (
            <ChevronRight className="mr-2 h-4 w-4" />
          )}
          {isLast ? "Submit All" : "Next Question"}
        </Button>
      </div>
    </div>
  );
}

// ── Main Component ────────────────────────────────────────────────────────────

export function CandidateRoomPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token");

  const [state, dispatch] = useReducer(reducer, {
    phase: token ? "LOADING" : "INVALID_TOKEN",
    currentQuestionIndex: 0,
    answers: {},
  });

  // ── Session query (poll every 10s while session is live) ──────────────────

  const { data: session } = useQuery({
    queryKey: ["candidate", "session", sessionId],
    queryFn: () => candidateRoomApi.getSession(),
    enabled: !!sessionId && !!token,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (!status) return false;
      return ["INVITED", "STARTED"].includes(status) ? 10_000 : false;
    },
  });

  // ── Questions query (load once session is in answering phase) ─────────────

  const { data: questions = [] } = useQuery({
    queryKey: ["candidate", "questions", sessionId],
    queryFn: () => candidateRoomApi.getQuestions(sessionId!),
    enabled:
      !!sessionId &&
      !!token &&
      (state.phase === "ANSWERING" ||
        state.phase === "SUBMITTED" ||
        state.phase === "COMPLETED"),
  });

  // ── Derive phase from session status ──────────────────────────────────────

  useEffect(() => {
    if (!session) return;

    const status = session.status;

    if (status === "CANCELLED") {
      dispatch({ type: "SET_PHASE", phase: "CANCELLED" });
    } else if (status === "EXPIRED" || status === "ERROR") {
      dispatch({ type: "SET_PHASE", phase: "FAILED" });
    } else if (status === "COMPLETED") {
      // Only move to COMPLETED if we haven't started answering yet;
      // if we're in ANSWERING or SUBMITTED, let those phases finish naturally.
      if (state.phase !== "ANSWERING" && state.phase !== "SUBMITTED") {
        dispatch({ type: "SET_PHASE", phase: "COMPLETED" });
      }
    } else if (status === "STARTED") {
      if (state.phase === "LOADING" || state.phase === "WAITING") {
        dispatch({ type: "SET_PHASE", phase: "IN_PROGRESS" });
      }
    } else if (status === "INVITED") {
      if (state.phase === "LOADING") {
        dispatch({ type: "SET_PHASE", phase: "WAITING" });
      }
    }
  }, [session, state.phase]);

  // ── Guard: no token ───────────────────────────────────────────────────────

  if (state.phase === "INVALID_TOKEN") {
    return (
      <ErrorScreen
        title="Invalid interview link"
        description="This link is missing your invite token. Please check the email you received and use the original link."
      />
    );
  }

  // ── Loading ───────────────────────────────────────────────────────────────

  if (state.phase === "LOADING") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4">
        <Loader2 className="h-10 w-10 animate-spin text-primary" />
        <p className="text-muted-foreground">Loading your interview…</p>
      </div>
    );
  }

  // ── Error states ──────────────────────────────────────────────────────────

  if (state.phase === "CANCELLED") {
    return (
      <ErrorScreen
        title="Interview cancelled"
        description="This interview session was cancelled. Please contact the hiring team if you believe this is a mistake."
      />
    );
  }

  if (state.phase === "FAILED") {
    return (
      <ErrorScreen
        title="Interview could not be completed"
        description="There was a technical issue with this session. Please contact the hiring team to reschedule."
      />
    );
  }

  // ── Answering ─────────────────────────────────────────────────────────────

  if (state.phase === "ANSWERING") {
    return (
      <AnsweringScreen
        questions={questions}
        state={state}
        dispatch={dispatch}
        sessionId={sessionId!}
        candidateName={"Candidate"}
      />
    );
  }

  if (state.phase === "SUBMITTED") {
    return <SubmittedScreen />;
  }

  if (state.phase === "COMPLETED") {
    return (
      <CompletedScreen candidateName={"Candidate"} />
    );
  }

  // ── Waiting / In-progress ─────────────────────────────────────────────────

  if (!session) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
        <p className="text-sm text-muted-foreground">Connecting…</p>
      </div>
    );
  }

  // Session info banner (visible in WAITING + IN_PROGRESS)
  const sessionBanner = (
    <div className="border-b bg-muted/30 px-6 py-3">
      <div className="mx-auto flex max-w-3xl items-center justify-between gap-4">
        <div>
          <p className="text-sm font-semibold">InterviewIQ Session</p>
          <p className="font-mono text-xs text-muted-foreground">
            {session.jobOpeningId.slice(0, 8)}…
          </p>
        </div>
        <Badge
          variant="outline"
          className={cn(
            "text-xs",
            state.phase === "IN_PROGRESS" &&
              "border-green-300 bg-green-50 text-green-700",
          )}
        >
          {state.phase === "IN_PROGRESS"
            ? "● Live"
            : session.scheduledAt
            ? formatDateTime(session.scheduledAt)
            : "Scheduled"}
        </Badge>
      </div>
    </div>
  );

  return (
    <div className="flex flex-1 flex-col">
      {sessionBanner}

      {state.phase === "WAITING" ? (
        <WaitingScreen
          sessionData={session}
          onStartAnswering={() =>
            dispatch({ type: "SET_PHASE", phase: "ANSWERING" })
          }
        />
      ) : (
        <InProgressScreen meetUrl={session.googleMeetUrl ?? undefined} />
      )}
    </div>
  );
}
