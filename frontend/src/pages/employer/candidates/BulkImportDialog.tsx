// =============================================================================
// BulkImportDialog.tsx — bulk candidate import by CSV
//
// PRD v2.1 §7.3.1: upload → column mapping → validation preview → confirm.
//
// The steps are separate for two reasons the UI has to carry:
//
//   MAPPING IS CONFIRMED, NOT GUESSED. The server proposes a mapping from the
//   header row and this screen asks the recruiter to confirm or correct it.
//   "We do not guess silently" — a column called "Contact" could be an email or
//   a phone, and quietly picking one produces fifty unusable candidate records.
//
//   NOTHING IS CHARGED UNTIL CONFIRM. The preview reports exactly what will
//   happen and takes no wallet reservation. Confirm takes the whole batch
//   atomically, because "a 50-candidate import that runs out of money at
//   candidate 38 is a support ticket and a half-imported opening".
// =============================================================================

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { CheckCircle2, FileUp, Loader2 } from "lucide-react";

import {
  candidateImportApi,
  type ColumnMapping,
  type ImportPreview,
} from "@/api/modules/candidateImport";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { cn, formatRupees } from "@/lib/utils";

type Step = "UPLOAD" | "MAPPING" | "PREVIEW" | "DONE";

/** Fields the mapping can target. Name and email are required (§7.3.1). */
const FIELDS = [
  { key: "name", label: "Full name", required: true },
  { key: "email", label: "Email", required: true },
  { key: "phone", label: "Phone", required: false },
  { key: "resumeUrl", label: "Resume URL", required: false },
] as const;

interface BulkImportDialogProps {
  jobOpeningId: string;
  onClose: () => void;
  onImported?: (count: number) => void;
}

export function BulkImportDialog({ jobOpeningId, onClose, onImported }: BulkImportDialogProps) {
  const qc = useQueryClient();

  const [step, setStep] = useState<Step>("UPLOAD");
  const [file, setFile] = useState<File | null>(null);
  const [header, setHeader] = useState<string[]>([]);
  const [mapping, setMapping] = useState<ColumnMapping>({});
  const [preview, setPreview] = useState<ImportPreview | null>(null);

  // ── Step 1 → 2: read the header, propose a mapping ───────────────────────

  const mappingMutation = useMutation({
    mutationFn: (chosen: File) => candidateImportApi.proposeMapping(jobOpeningId, chosen),
    onSuccess(data) {
      setHeader(data.header);
      setMapping(data.proposedMapping);
      setStep("MAPPING");
    },
    onError(error) {
      toast.error(error instanceof AppError ? error.message : "Could not read that file.");
    },
  });

  // ── Step 2 → 3: validate. Charges nothing. ───────────────────────────────

  const previewMutation = useMutation({
    mutationFn: () => candidateImportApi.preview(jobOpeningId, file!, mapping),
    onSuccess(data) {
      setPreview(data);
      setStep("PREVIEW");
    },
    onError(error) {
      toast.error(error instanceof AppError ? error.message : "Could not validate that file.");
    },
  });

  // ── Step 3 → done: atomic whole-batch reservation ────────────────────────

  const confirmMutation = useMutation({
    mutationFn: () => candidateImportApi.confirm(preview!.batchId, file!, mapping),
    onSuccess(result) {
      toast.success(`Imported ${result.importedCount} candidate(s).`);
      void qc.invalidateQueries({ queryKey: ["candidates"] });
      void qc.invalidateQueries({ queryKey: ["billing", "wallet"] });
      onImported?.(result.importedCount);
      setStep("DONE");
    },
    onError(error) {
      // Insufficient balance refuses the WHOLE import — nothing was written, so
      // the recruiter can top up and retry the same file unchanged.
      toast.error(
        error instanceof AppError
          ? error.message
          : "Could not import. No candidates were added.",
      );
    },
  });

  const requiredMissing = FIELDS.filter((f) => f.required && mapping[f.key] === undefined);

  return (
    <div className="space-y-4">
      <StepIndicator step={step} />

      {/* ── Step 1: choose a file ──────────────────────────────────────── */}
      {step === "UPLOAD" && (
        <Card>
          <CardContent className="space-y-4 pt-6">
            <div className="rounded-md border-2 border-dashed p-8 text-center">
              <FileUp className="mx-auto h-8 w-8 text-muted-foreground" />
              <p className="mt-2 text-sm font-medium">Choose a CSV of candidates</p>
              <p className="mt-1 text-xs text-muted-foreground">
                Needs a name and an email per row. Phone is optional. Up to 200 candidates.
              </p>
              <input
                type="file"
                accept=".csv,text/csv"
                className="mt-4 text-sm"
                onChange={(e) => {
                  const chosen = e.target.files?.[0];
                  if (chosen) {
                    setFile(chosen);
                    mappingMutation.mutate(chosen);
                  }
                }}
              />
            </div>

            {/* Résumés cannot come through a CSV (§7.3.1) — say so up front
                rather than letting a recruiter wonder where they went. */}
            <p className="text-xs text-muted-foreground">
              Résumés can&apos;t be included in a CSV. Imported candidates are interviewed
              from the job description alone unless you add a résumé afterwards.
            </p>

            {mappingMutation.isPending && (
              <p className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                Reading the file…
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Step 2: confirm the mapping ────────────────────────────────── */}
      {step === "MAPPING" && (
        <Card>
          <CardContent className="space-y-4 pt-6">
            <div>
              <h3 className="font-medium">Which column is which?</h3>
              <p className="mt-1 text-sm text-muted-foreground">
                We&apos;ve guessed from your header row. Please check it — we won&apos;t
                assume.
              </p>
            </div>

            <div className="space-y-3">
              {FIELDS.map((field) => (
                <div key={field.key} className="flex items-center gap-3">
                  <Label className="w-32 shrink-0 text-sm">
                    {field.label}
                    {field.required && <span className="ml-1 text-destructive">*</span>}
                  </Label>
                  <select
                    value={mapping[field.key] ?? ""}
                    onChange={(e) =>
                      setMapping((prev) => {
                        const next = { ...prev };
                        if (e.target.value === "") delete next[field.key];
                        else next[field.key] = Number(e.target.value);
                        return next;
                      })
                    }
                    className="flex-1 rounded-md border bg-background px-3 py-2 text-sm"
                  >
                    <option value="">— not in this file —</option>
                    {header.map((column, index) => (
                      <option key={index} value={index}>
                        {column || `Column ${index + 1}`}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setStep("UPLOAD")}>
                Back
              </Button>
              <Button
                onClick={() => previewMutation.mutate()}
                disabled={requiredMissing.length > 0 || previewMutation.isPending}
              >
                {previewMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Check the file
              </Button>
            </div>

            {requiredMissing.length > 0 && (
              <p className="text-xs text-destructive">
                Still need a column for: {requiredMissing.map((f) => f.label).join(", ")}.
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Step 3: the preview ────────────────────────────────────────── */}
      {step === "PREVIEW" && preview && (
        <Card>
          <CardContent className="space-y-4 pt-6">
            <div>
              <h3 className="font-medium">Here&apos;s what will happen</h3>
              {/* The exact summary form §7.3.1 specifies. */}
              <p className="mt-1 text-sm text-muted-foreground">{preview.summary}</p>
            </div>

            <div className="grid grid-cols-3 gap-3">
              <Stat label="Will import" value={preview.validCount} tone="good" />
              <Stat label="Already added" value={preview.duplicateCount} tone="muted" />
              <Stat label="Can't read" value={preview.invalidCount} tone="bad" />
            </div>

            {/* Reservation stated BEFORE the recruiter commits. */}
            <div className="rounded-md bg-muted p-3 text-sm">
              <p>
                This will reserve{" "}
                <strong>{formatRupees(preview.reservationRequiredPaise)}</strong> from your
                wallet — one interview per candidate. If your balance won&apos;t cover the
                whole batch, nothing is imported.
              </p>
            </div>

            {preview.rows.some((r) => r.outcome !== "VALID") && (
              <div className="max-h-56 space-y-1 overflow-y-auto rounded-md border p-2">
                {preview.rows
                  .filter((r) => r.outcome !== "VALID")
                  .map((row) => (
                    <div key={row.lineNumber} className="flex gap-2 text-xs">
                      <span className="w-14 shrink-0 text-muted-foreground">
                        Line {row.lineNumber}
                      </span>
                      <span className="w-44 shrink-0 truncate">{row.email || row.name || "—"}</span>
                      <span
                        className={cn(
                          row.outcome === "DUPLICATE" ? "text-muted-foreground" : "text-destructive",
                        )}
                      >
                        {row.problems.join("; ")}
                      </span>
                    </div>
                  ))}
              </div>
            )}

            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setStep("MAPPING")}>
                Back
              </Button>
              <Button
                onClick={() => confirmMutation.mutate()}
                disabled={preview.validCount === 0 || confirmMutation.isPending}
              >
                {confirmMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Import {preview.validCount} candidate(s)
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* ── Done ───────────────────────────────────────────────────────── */}
      {step === "DONE" && (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-8 text-center">
            <CheckCircle2 className="h-10 w-10 text-green-600" />
            <p className="font-medium">Candidates imported</p>
            <p className="text-sm text-muted-foreground">
              They haven&apos;t been invited yet — send invites when you&apos;re ready.
            </p>
            <Button onClick={onClose}>Done</Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function StepIndicator({ step }: { step: Step }) {
  const steps: { key: Step; label: string }[] = [
    { key: "UPLOAD", label: "Choose file" },
    { key: "MAPPING", label: "Match columns" },
    { key: "PREVIEW", label: "Review" },
    { key: "DONE", label: "Done" },
  ];
  const currentIndex = steps.findIndex((s) => s.key === step);

  return (
    <div className="flex items-center gap-2 text-xs">
      {steps.map((s, i) => (
        <div key={s.key} className="flex items-center gap-2">
          <span
            className={cn(
              "rounded-full px-2 py-1",
              i <= currentIndex ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground",
            )}
          >
            {s.label}
          </span>
          {i < steps.length - 1 && <span className="text-muted-foreground">→</span>}
        </div>
      ))}
    </div>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: "good" | "bad" | "muted";
}) {
  return (
    <div className="rounded-md border p-3 text-center">
      <p
        className={cn(
          "text-2xl font-bold",
          tone === "good" && "text-green-600",
          tone === "bad" && "text-destructive",
          tone === "muted" && "text-muted-foreground",
        )}
      >
        {value}
      </p>
      <p className="text-xs text-muted-foreground">{label}</p>
    </div>
  );
}
