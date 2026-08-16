// =============================================================================
// EvidencePanel.tsx — per-question narrative evidence on the report
//
// PRD v2.1 §7.6, and it is unusually firm about this:
//
//   "Every evaluation report carries per-question narrative evidence, and every
//    claim cites a specific answer — never a bare score. A recruiter who can see
//    WHY the score is 72 will trust and act on it; a bare '72' gets ignored. The
//    quoted evidence is also the best defence if a candidate ever challenges a
//    decision."
//
// So this component's job is to make the citations navigable: a claim about
// Technical Depth links to the answers that support it, and clicking through
// takes the recruiter to the transcript rather than asking them to trust a
// number.
//
// It also carries two things the spec requires be visible and which are easy to
// leave out:
//
//   • EMPLOYER source labels — "a recruiter reading a low Technical score needs
//     to know whether it came from our questions or theirs" (§7.5.8).
//   • The advisory-only disclaimer (§7.10). The platform performs no automated
//     rejection and a human makes every hiring decision. It appears on the
//     report page, not only in the contract.
// =============================================================================

import { useState } from "react";
import { AlertTriangle, Info, Quote, User } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import type { Evidence, SessionAnswer } from "@/types";
import { cn } from "@/lib/utils";

const DIMENSION_LABELS: Record<string, string> = {
  TECHNICAL: "Technical depth",
  COMMUNICATION: "Communication clarity",
  RELEVANCE: "Relevance to the role",
  PROBLEM_SOLVING: "Problem-solving approach",
};

interface EvidencePanelProps {
  evidence: Evidence;
  answers: SessionAnswer[];
  /** Set when the candidate answered some but not all questions (§7.5.7). */
  partial?: boolean;
}

export function EvidencePanel({ evidence, answers, partial }: EvidencePanelProps) {
  const [highlighted, setHighlighted] = useState<number[]>([]);

  const answerByIndex = new Map(answers.map((a) => [a.questionIndex, a]));

  return (
    <div className="space-y-4">
      {/* ── Incomplete flag ────────────────────────────────────────────── */}
      {partial && (
        <Card className="border-amber-200 bg-amber-50">
          <CardContent className="flex gap-3 pt-4 text-sm text-amber-900">
            <AlertTriangle className="h-5 w-5 shrink-0" />
            <div>
              <p className="font-medium">Incomplete interview</p>
              <p className="mt-0.5">
                This candidate didn&apos;t answer every question. The score reflects only
                what they did answer — read it alongside the transcript rather than
                comparing it directly with a completed interview.
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {/* ── Summary ────────────────────────────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Summary</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="pt-4">
          <p className="text-sm leading-relaxed">{evidence.overallSummary}</p>
        </CardContent>
      </Card>

      {/* ── Per-dimension narrative, with citations ────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Why this score</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="space-y-4 pt-4">
          {Object.entries(evidence.dimensions ?? {}).map(([dimension, detail]) => (
            <div key={dimension}>
              <p className="text-sm font-medium">
                {DIMENSION_LABELS[dimension] ?? dimension}
              </p>
              <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                {detail.narrative}
              </p>

              {detail.citedAnswerIndexes?.length > 0 && (
                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  <Quote className="h-3 w-3 text-muted-foreground" />
                  <span className="text-xs text-muted-foreground">Based on</span>
                  {detail.citedAnswerIndexes.map((index) => (
                    <button
                      key={index}
                      type="button"
                      onMouseEnter={() => setHighlighted(detail.citedAnswerIndexes)}
                      onMouseLeave={() => setHighlighted([])}
                      onClick={() => {
                        document
                          .getElementById(`answer-${index}`)
                          ?.scrollIntoView({ behavior: "smooth", block: "center" });
                        setHighlighted([index]);
                      }}
                      className="rounded border px-1.5 py-0.5 text-xs hover:bg-muted"
                    >
                      Q{index + 1}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      {/* ── Transcript, question by question ───────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Transcript and per-question notes</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="space-y-3 pt-4">
          {evidence.perQuestion?.map((entry) => {
            const answer = answerByIndex.get(entry.questionIndex);
            const isHighlighted = highlighted.includes(entry.questionIndex);

            return (
              <div
                key={entry.questionIndex}
                id={`answer-${entry.questionIndex}`}
                className={cn(
                  "rounded-md border p-3 transition-colors",
                  isHighlighted && "border-primary bg-primary/5",
                )}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-xs font-medium text-muted-foreground">
                        Q{entry.questionIndex + 1}
                      </span>

                      {/* §7.5.8: the recruiter must be able to tell whose
                          question produced this score. */}
                      {answer?.questionSource === "EMPLOYER" && (
                        <span className="inline-flex items-center gap-1 rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-800">
                          <User className="h-3 w-3" />
                          Your question
                        </span>
                      )}

                      {answer?.isFollowUp && (
                        <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">
                          Follow-up
                        </span>
                      )}
                    </div>

                    <p className="mt-1 text-sm font-medium">
                      {answer?.questionText ?? "(question text unavailable)"}
                    </p>
                  </div>

                  {entry.score != null && (
                    <span className="shrink-0 text-sm font-semibold">{entry.score}/10</span>
                  )}
                </div>

                <div className="mt-2 space-y-2">
                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                      What they said
                    </p>
                    <p className="mt-0.5 text-sm">
                      {answer?.skipped || !answer?.transcriptText ? (
                        <span className="italic text-muted-foreground">
                          No answer given — this question was skipped.
                        </span>
                      ) : (
                        answer.transcriptText
                      )}
                    </p>
                  </div>

                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                      Assessment
                    </p>
                    <p className="mt-0.5 text-sm text-muted-foreground">{entry.narrative}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>

      {/* ── The advisory-only guarantee (§7.10) ────────────────────────── */}
      <Card className="bg-muted/50">
        <CardContent className="flex gap-3 pt-4 text-xs text-muted-foreground">
          <Info className="h-4 w-4 shrink-0" />
          <p>
            This score is <strong>advisory only</strong>. InterviewIQ does not reject or
            advance any candidate on its own — a human recruiter makes every hiring
            decision. Use this report as one input alongside your own judgement.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
