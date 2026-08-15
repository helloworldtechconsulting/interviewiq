// =============================================================================
// QuestionBankPanel.tsx — the employer's own questions for a job
//
// PRD v2.1 §7.5.8. Optional; the default remains 100% AI-generated. Where it is
// used, employer questions occupy the CORE segment first, so every candidate for
// the job is asked them and stays comparable.
//
// THE TWO RULES THIS UI HAS TO MAKE VISIBLE:
//
//   1. Employer questions still pass the prohibited-topic safety filter. A
//      partially-refused upload is the NORMAL case, not an error state — a
//      recruiter pastes ten questions and one touches marital status. So the
//      result is rendered per question, with the refused ones showing WHICH
//      category they touched, because §7.5.8 requires the refusal to name it so
//      the employer can correct the question.
//
//   2. They bypass the quality critic but never the safety filter. There is
//      deliberately no "use anyway" affordance on a refused question — the only
//      way forward is to rewrite it.
// =============================================================================

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  AlertTriangle,
  CheckCircle2,
  Loader2,
  Trash2,
  Upload,
  ArrowUp,
  ArrowDown,
} from "lucide-react";

import {
  employerQuestionsApi,
  type EmployerQuestion,
} from "@/api/modules/employerQuestions";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";

interface QuestionBankPanelProps {
  jobId: string;
}

export function QuestionBankPanel({ jobId }: QuestionBankPanelProps) {
  const qc = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [pastedText, setPastedText] = useState("");

  const { data: questions = [], isLoading } = useQuery({
    queryKey: ["jobs", jobId, "questions"],
    queryFn: () => employerQuestionsApi.list(jobId),
  });

  const uploadMutation = useMutation({
    mutationFn: (payload: { questions?: string[]; pastedText?: string }) =>
      employerQuestionsApi.upload(jobId, payload),
    onSuccess(result) {
      setPastedText("");
      if (fileInput.current) fileInput.current.value = "";

      // A partial refusal is reported plainly rather than as a failure. The
      // per-question detail below shows which ones and why.
      if (result.refusedCount === 0) {
        toast.success(`Added ${result.acceptedCount} question(s).`);
      } else {
        toast.warning(
          `Added ${result.acceptedCount}. ${result.refusedCount} could not be used — see below.`,
        );
      }
      void qc.invalidateQueries({ queryKey: ["jobs", jobId, "questions"] });
    },
    onError(error) {
      toast.error(error instanceof AppError ? error.message : "Could not add those questions.");
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (questionId: string) => employerQuestionsApi.delete(jobId, questionId),
    onSuccess() {
      void qc.invalidateQueries({ queryKey: ["jobs", jobId, "questions"] });
    },
  });

  const reorderMutation = useMutation({
    mutationFn: (ids: string[]) => employerQuestionsApi.reorder(jobId, ids),
    onSuccess() {
      void qc.invalidateQueries({ queryKey: ["jobs", jobId, "questions"] });
    },
  });

  /** Reads a CSV client-side; the first column of each row is the question. */
  const handleFile = async (file: File) => {
    const text = await file.text();
    const lines = text
      .split(/\r?\n/)
      .map((line) => line.split(",")[0]?.replace(/^"|"$/g, "").trim())
      .filter((line): line is string => !!line && line.length > 0);

    // Drop a header row if it looks like one rather than a question.
    const first = lines[0]?.toLowerCase();
    const rows = first === "question" || first === "questions" ? lines.slice(1) : lines;

    if (rows.length === 0) {
      toast.error("That file contained no questions.");
      return;
    }
    uploadMutation.mutate({ questions: rows });
  };

  const move = (index: number, direction: -1 | 1) => {
    const next = [...questions];
    const target = index + direction;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    reorderMutation.mutate(next.map((q) => q.id));
  };

  const usableCount = questions.filter((q) => q.usable).length;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Your own questions</CardTitle>
      </CardHeader>
      <Separator />

      <CardContent className="space-y-5 pt-4">
        <p className="text-sm text-muted-foreground">
          Optional. Questions you add here are asked to <strong>every</strong> candidate
          for this opening, so their answers stay comparable. The AI fills the rest of
          the interview around them.
        </p>

        {/* ── Add ──────────────────────────────────────────────────────── */}
        <div className="space-y-3">
          <textarea
            value={pastedText}
            onChange={(e) => setPastedText(e.target.value)}
            rows={4}
            placeholder={"One question per line…\nWhat is the hardest bug you have shipped a fix for?"}
            className="w-full rounded-md border bg-background p-3 text-sm"
          />

          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              onClick={() => uploadMutation.mutate({ pastedText })}
              disabled={!pastedText.trim() || uploadMutation.isPending}
            >
              {uploadMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Add questions
            </Button>

            <input
              ref={fileInput}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void handleFile(file);
              }}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={() => fileInput.current?.click()}
              disabled={uploadMutation.isPending}
            >
              <Upload className="mr-2 h-4 w-4" />
              Upload CSV
            </Button>
          </div>

          {/* Set expectations before they paste something that will be refused. */}
          <p className="text-xs text-muted-foreground">
            Questions are checked before use. We cannot ask about age, gender, religion,
            caste, marital status, family plans, pregnancy, disability or national origin
            — for any candidate, on any opening.
          </p>
        </div>

        {/* ── The bank ─────────────────────────────────────────────────── */}
        {isLoading ? (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading…
          </div>
        ) : questions.length === 0 ? (
          <p className="py-4 text-sm text-muted-foreground">
            No custom questions yet. This opening will use AI-generated questions only.
          </p>
        ) : (
          <div className="space-y-2">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {usableCount} of {questions.length} in use
            </p>

            {questions.map((question, index) => (
              <QuestionRow
                key={question.id}
                question={question}
                isFirst={index === 0}
                isLast={index === questions.length - 1}
                onMoveUp={() => move(index, -1)}
                onMoveDown={() => move(index, 1)}
                onDelete={() => deleteMutation.mutate(question.id)}
              />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function QuestionRow({
  question,
  isFirst,
  isLast,
  onMoveUp,
  onMoveDown,
  onDelete,
}: {
  question: EmployerQuestion;
  isFirst: boolean;
  isLast: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onDelete: () => void;
}) {
  const refused = !question.usable;

  return (
    <div
      className={cn(
        "flex items-start gap-3 rounded-md border p-3",
        refused && "border-destructive/40 bg-destructive/5",
      )}
    >
      {refused ? (
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
      ) : (
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-green-600" />
      )}

      <div className="min-w-0 flex-1">
        <p className={cn("text-sm", refused && "text-muted-foreground line-through")}>
          {question.questionText}
        </p>

        {refused && question.rejectionReason && (
          // §7.5.8: the refusal names the category so the employer can correct
          // the question rather than guess at what was wrong with it.
          <p className="mt-1 text-xs text-destructive">
            Can&apos;t be used — this asks about {question.rejectionReason}. Rewrite it
            without that and add it again.
          </p>
        )}
      </div>

      <div className="flex shrink-0 items-center gap-1">
        {!refused && (
          <>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Move up"
              onClick={onMoveUp}
              disabled={isFirst}
            >
              <ArrowUp className="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Move down"
              onClick={onMoveDown}
              disabled={isLast}
            >
              <ArrowDown className="h-3.5 w-3.5" />
            </Button>
          </>
        )}
        <Button variant="ghost" size="icon" aria-label="Delete question" onClick={onDelete}>
          <Trash2 className="h-3.5 w-3.5 text-destructive" />
        </Button>
      </div>
    </div>
  );
}
