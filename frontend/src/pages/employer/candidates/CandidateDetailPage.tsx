// =============================================================================
// CandidateDetailPage.tsx
// — View/edit candidate, upload resume, update status, schedule AI session
// =============================================================================

import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import {
  ArrowLeft,
  Upload,
  Mail,
  Send,
  Calendar,
  Loader2,
  Save,
  Pencil,
  X,
} from "lucide-react";

import { candidatesApi } from "@/api/modules/candidates";
import { sessionsApi } from "@/api/modules/sessions";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import type { CandidateStatus } from "@/types";
import { PageHeader } from "@/components/common/PageHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { EmptyState } from "@/components/common/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { formatDate, formatDateTime, uploadToS3 } from "@/lib/utils";

// ── Schedule session schema ───────────────────────────────────────────────────

const scheduleSchema = z.object({
  scheduledAt: z.string().min(1, "Please pick a date and time"),
  durationMinutes: z.coerce
    .number()
    .min(15, "Minimum 15 minutes")
    .max(180, "Maximum 3 hours"),
});

type ScheduleForm = z.infer<typeof scheduleSchema>;

const CANDIDATE_STATUSES: CandidateStatus[] = [
  "APPLIED",
  "SCREENING",
  "INTERVIEW_SCHEDULED",
  "INTERVIEW_DONE",
  "OFFER_EXTENDED",
  "HIRED",
  "REJECTED",
  "WITHDRAWN",
];

// ── Schedule Session Dialog ───────────────────────────────────────────────────

interface ScheduleDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  candidateId: string;
  candidateName: string;
}

function ScheduleSessionDialog({
  open,
  onOpenChange,
  candidateId,
  candidateName,
}: ScheduleDialogProps) {
  const qc = useQueryClient();
  const navigate = useNavigate();

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ScheduleForm>({
    resolver: zodResolver(scheduleSchema),
    defaultValues: { durationMinutes: 60 },
  });

  const mutation = useMutation({
    mutationFn: (data: ScheduleForm) =>
      sessionsApi.create({
        candidateId,
        scheduledAt: new Date(data.scheduledAt).toISOString(),
        durationMinutes: data.durationMinutes,
      }),
    onSuccess(session) {
      toast.success("AI interview session scheduled.");
      void qc.invalidateQueries({ queryKey: queryKeys.sessions.all() });
      reset();
      onOpenChange(false);
      navigate(`/app/sessions/${session.id}`);
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not schedule session.",
      );
    },
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!mutation.isPending) {
          reset();
          onOpenChange(v);
        }
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Schedule AI interview</DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">
          Schedule an AI-powered interview session for{" "}
          <span className="font-medium text-foreground">{candidateName}</span>.
          The AI bot will join the meeting, conduct the interview, and generate
          an evaluation report.
        </p>

        <form
          onSubmit={handleSubmit((d) => mutation.mutate(d))}
          noValidate
          className="space-y-4"
        >
          <div className="space-y-1.5">
            <Label htmlFor="scheduledAt">Date & time *</Label>
            <Input
              id="scheduledAt"
              type="datetime-local"
              min={new Date().toISOString().slice(0, 16)}
              {...register("scheduledAt")}
            />
            {errors.scheduledAt && (
              <p className="text-xs text-destructive">
                {errors.scheduledAt.message}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="durationMinutes">Duration (minutes)</Label>
            <Input
              id="durationMinutes"
              type="number"
              min={15}
              max={180}
              step={15}
              {...register("durationMinutes")}
            />
            {errors.durationMinutes && (
              <p className="text-xs text-destructive">
                {errors.durationMinutes.message}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={mutation.isPending}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending && (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              )}
              Schedule
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ── Main Component ────────────────────────────────────────────────────────────

export function CandidateDetailPage() {
  const { candidateId } = useParams<{ candidateId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [editing, setEditing] = useState(false);
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);

  // ── Queries ────────────────────────────────────────────────────────────────

  const {
    data: candidate,
    isLoading,
    isError,
  } = useQuery({
    queryKey: queryKeys.candidates.detail(candidateId!),
    queryFn: () => candidatesApi.get(candidateId!),
    enabled: !!candidateId,
  });

  const { data: sessionsPage } = useQuery({
    queryKey: queryKeys.sessions.list({ candidateId }),
    queryFn: () => sessionsApi.list({ candidateId, size: 10 }),
    enabled: !!candidateId,
  });

  // ── Edit form ──────────────────────────────────────────────────────────────

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    setError,
    formState: { errors, isDirty },
  } = useForm<{ fullName: string; phone: string; status: CandidateStatus }>({
    values: candidate
      ? {
          fullName: candidate.fullName,
          phone: candidate.phone ?? "",
          status: candidate.status,
        }
      : undefined,
  });

  const currentStatus = watch("status");

  // ── Mutations ──────────────────────────────────────────────────────────────

  const updateMutation = useMutation({
    mutationFn: (data: {
      fullName: string;
      phone: string;
      status: CandidateStatus;
    }) =>
      candidatesApi.update(candidateId!, {
        fullName: data.fullName,
        phone: data.phone || undefined,
        status: data.status,
      }),
    onSuccess() {
      toast.success("Candidate updated.");
      void qc.invalidateQueries({ queryKey: queryKeys.candidates.all() });
      setEditing(false);
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, msg]) => {
          setError(
            field as "fullName" | "phone" | "status",
            { message: msg },
          );
        });
      } else {
        toast.error(
          error instanceof AppError ? error.message : "Update failed.",
        );
      }
    },
  });

  const inviteMutation = useMutation({
    mutationFn: () => candidatesApi.sendInvite(candidateId!),
    onSuccess() {
      toast.success("Invite email sent to candidate.");
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not send invite.",
      );
    },
  });

  // ── Resume upload ──────────────────────────────────────────────────────────

  async function handleResumeUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file || !candidateId) return;
    try {
      setUploadProgress(0);
      const { uploadUrl } = await candidatesApi.getResumeUploadUrl(
        candidateId,
        file.name,
        file.type || "application/pdf",
      );
      await uploadToS3(uploadUrl, file, setUploadProgress);
      toast.success("Resume uploaded.");
      void qc.invalidateQueries({
        queryKey: queryKeys.candidates.detail(candidateId),
      });
    } catch {
      toast.error("Upload failed. Please try again.");
    } finally {
      setUploadProgress(null);
      e.target.value = "";
    }
  }

  // ── Loading / error ────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-48 animate-pulse rounded bg-muted" />
        <div className="h-40 animate-pulse rounded-lg bg-muted" />
      </div>
    );
  }

  if (isError || !candidate) {
    return (
      <EmptyState
        title="Candidate not found"
        description="This candidate may have been deleted."
        action={{
          label: "Back to Candidates",
          onClick: () => navigate("/app/candidates"),
        }}
      />
    );
  }

  const sessions = sessionsPage?.content ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title={candidate.fullName}
        description={`Applying for ${candidate.jobTitle}`}
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate("/app/candidates")}
            >
              <ArrowLeft className="mr-1 h-4 w-4" />
              Back
            </Button>
            {!editing ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setEditing(true)}
              >
                <Pencil className="mr-1 h-4 w-4" />
                Edit
              </Button>
            ) : (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    reset();
                    setEditing(false);
                  }}
                  disabled={updateMutation.isPending}
                >
                  <X className="mr-1 h-4 w-4" />
                  Cancel
                </Button>
                <Button
                  size="sm"
                  onClick={handleSubmit((d) => updateMutation.mutate(d))}
                  disabled={updateMutation.isPending || !isDirty}
                >
                  {updateMutation.isPending ? (
                    <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                  ) : (
                    <Save className="mr-1 h-4 w-4" />
                  )}
                  Save
                </Button>
              </>
            )}
            <Button
              size="sm"
              onClick={() => setScheduleOpen(true)}
            >
              <Calendar className="mr-1 h-4 w-4" />
              Schedule Interview
            </Button>
          </div>
        }
      />

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ── Details ─────────────────────────────────────────────────────── */}
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Candidate Details</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="space-y-4 pt-4">
              {!editing ? (
                <>
                  <div className="flex items-center gap-2">
                    <StatusBadge kind="candidate" status={candidate.status} />
                  </div>
                  <dl className="grid gap-3 sm:grid-cols-2">
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Email
                      </dt>
                      <dd className="mt-1 flex items-center gap-1.5 text-sm">
                        <Mail className="h-3.5 w-3.5 text-muted-foreground" />
                        <a
                          href={`mailto:${candidate.email}`}
                          className="hover:underline"
                        >
                          {candidate.email}
                        </a>
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Phone
                      </dt>
                      <dd className="mt-1 text-sm">
                        {candidate.phone ?? "—"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Applied
                      </dt>
                      <dd className="mt-1 text-sm">
                        {formatDate(candidate.createdAt)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Last updated
                      </dt>
                      <dd className="mt-1 text-sm">
                        {formatDate(candidate.updatedAt)}
                      </dd>
                    </div>
                  </dl>
                </>
              ) : (
                <div className="space-y-4">
                  {/* Status */}
                  <div className="space-y-1.5">
                    <Label>Status</Label>
                    <Select
                      value={currentStatus}
                      onValueChange={(v) =>
                        setValue("status", v as CandidateStatus, {
                          shouldDirty: true,
                        })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {CANDIDATE_STATUSES.map((s) => (
                          <SelectItem key={s} value={s}>
                            {s
                              .replace(/_/g, " ")
                              .charAt(0)
                              .toUpperCase() +
                              s
                                .replace(/_/g, " ")
                                .slice(1)
                                .toLowerCase()}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="fullName">Full name</Label>
                    <Input id="fullName" {...register("fullName")} />
                    {errors.fullName && (
                      <p className="text-xs text-destructive">
                        {errors.fullName.message}
                      </p>
                    )}
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="phone">Phone</Label>
                    <Input id="phone" type="tel" {...register("phone")} />
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* ── Sessions timeline ────────────────────────────────────────────── */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2 text-base">
                <Calendar className="h-4 w-4" />
                Interview Sessions ({sessionsPage?.totalElements ?? 0})
              </CardTitle>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setScheduleOpen(true)}
              >
                + Schedule
              </Button>
            </CardHeader>
            <Separator />
            <CardContent className="p-0">
              {sessions.length === 0 ? (
                <div className="py-8 text-center text-sm text-muted-foreground">
                  No sessions scheduled yet.
                </div>
              ) : (
                <ul className="divide-y">
                  {sessions.map((s) => (
                    <li
                      key={s.id}
                      className="flex cursor-pointer items-center justify-between gap-4 px-6 py-4 hover:bg-muted/50"
                      onClick={() => navigate(`/app/sessions/${s.id}`)}
                    >
                      <div>
                        <p className="text-sm font-medium">
                          {formatDateTime(s.scheduledAt)}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {s.durationMinutes} min · {s.jobTitle}
                        </p>
                      </div>
                      <StatusBadge kind="session" status={s.status} />
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </div>

        {/* ── Sidebar ─────────────────────────────────────────────────────── */}
        <div className="space-y-4">
          {/* Resume upload */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Resume</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <p className="mb-3 text-sm text-muted-foreground">
                Upload the candidate's resume (PDF or Word). The AI uses it for
                personalised interview questions.
              </p>
              <label className="block">
                <input
                  type="file"
                  accept=".pdf,.doc,.docx"
                  className="sr-only"
                  onChange={handleResumeUpload}
                  disabled={uploadProgress !== null}
                />
                <Button
                  variant="outline"
                  className="w-full"
                  asChild
                  disabled={uploadProgress !== null}
                >
                  <span>
                    {uploadProgress !== null ? (
                      <>
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        Uploading {uploadProgress}%
                      </>
                    ) : (
                      <>
                        <Upload className="mr-2 h-4 w-4" />
                        {candidate.resumeKey
                          ? "Replace Resume"
                          : "Upload Resume"}
                      </>
                    )}
                  </span>
                </Button>
              </label>
              {candidate.resumeKey && uploadProgress === null && (
                <p className="mt-2 text-center text-xs text-green-600">
                  ✓ Resume on file
                </p>
              )}
              {uploadProgress !== null && (
                <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full bg-primary transition-all"
                    style={{ width: `${uploadProgress}%` }}
                  />
                </div>
              )}
            </CardContent>
          </Card>

          {/* Send invite */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Candidate Portal</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <p className="mb-3 text-sm text-muted-foreground">
                Send the candidate a magic link to access their interview room.
              </p>
              <Button
                variant="outline"
                className="w-full"
                onClick={() => inviteMutation.mutate()}
                disabled={inviteMutation.isPending}
              >
                {inviteMutation.isPending ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <Send className="mr-2 h-4 w-4" />
                )}
                Send Interview Invite
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>

      <ScheduleSessionDialog
        open={scheduleOpen}
        onOpenChange={setScheduleOpen}
        candidateId={candidate.id}
        candidateName={candidate.fullName}
      />
    </div>
  );
}
