// =============================================================================
// JobDetailPage.tsx — View/edit a job, upload JD, see its candidates
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
  Pencil,
  Trash2,
  Upload,
  Users,
  Loader2,
  Save,
  X,
} from "lucide-react";

import { jobsApi } from "@/api/modules/jobs";
import { candidatesApi } from "@/api/modules/candidates";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import type { JobStatus, LocationType, EmploymentType } from "@/types";
import { PageHeader } from "@/components/common/PageHeader";
import { QuestionBankPanel } from "./QuestionBankPanel";
import { DurationTierSelect } from "@/components/common/DurationTierSelect";
import type { DurationTier } from "@/types";
import { StatusBadge } from "@/components/common/StatusBadge";
import { EmptyState } from "@/components/common/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { formatDate, uploadToS3 } from "@/lib/utils";

// ── Edit schema ───────────────────────────────────────────────────────────────

const editSchema = z.object({
  title: z.string().min(2, "Title must be at least 2 characters"),
  department: z.string().optional(),
  locationType: z.enum(["REMOTE", "ONSITE", "HYBRID"]).optional(),
  employmentType: z
    .enum(["FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP"])
    .optional(),
  description: z.string().optional(),
  status: z.enum(["ACTIVE", "CLOSED", "ARCHIVED"]),
  experienceMin: z.coerce
    .number({ invalid_type_error: "Must be a number" })
    .int()
    .min(0, "Cannot be negative")
    .optional()
    .or(z.literal("")),
  experienceMax: z.coerce
    .number({ invalid_type_error: "Must be a number" })
    .int()
    .min(0, "Cannot be negative")
    .optional()
    .or(z.literal("")),
}).refine(
  (data) => {
    const min = data.experienceMin;
    const max = data.experienceMax;
    if (min !== undefined && min !== "" && max !== undefined && max !== "") {
      return Number(max) >= Number(min);
    }
    return true;
  },
  { message: "Max experience must be ≥ min experience", path: ["experienceMax"] },
);

type EditForm = z.infer<typeof editSchema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function JobDetailPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);

  // ── Queries ────────────────────────────────────────────────────────────────

  const {
    data: job,
    isLoading: jobLoading,
    isError,
  } = useQuery({
    queryKey: queryKeys.jobs.detail(jobId!),
    queryFn: () => jobsApi.get(jobId!),
    enabled: !!jobId,
  });

  const { data: candidatesPage, isLoading: candidatesLoading } = useQuery({
    queryKey: queryKeys.candidates.list({ jobOpeningId: jobId }),
    queryFn: () => candidatesApi.list({ jobOpeningId: jobId, size: 50 }),
    enabled: !!jobId,
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
  } = useForm<EditForm>({
    resolver: zodResolver(editSchema),
    values: job
      ? {
          title: job.title,
          department: job.department ?? "",
          locationType: job.locationType ?? undefined,
          employmentType: job.employmentType ?? undefined,
          description: job.description ?? "",
          status: job.status as JobStatus,
          experienceMin: job.experienceMin ?? "",
          experienceMax: job.experienceMax ?? "",
        }
      : undefined,
  });

  const currentStatus = watch("status");

  // ── Mutations ──────────────────────────────────────────────────────────────

  // Separate from the edit form: the tier is changed with one click from the
  // sidebar rather than by entering edit mode on the whole job.
  const tierMutation = useMutation({
    mutationFn: (durationTier: DurationTier) =>
      jobsApi.update(jobId!, { durationTier }),
    onSuccess() {
      toast.success("Interview length updated.");
      void qc.invalidateQueries({ queryKey: queryKeys.jobs.detail(jobId!) });
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not change the interview length.",
      );
    },
  });

  const updateMutation = useMutation({
    mutationFn: (data: EditForm) =>
      jobsApi.update(jobId!, {
        ...data,
        experienceMin: data.experienceMin === "" ? undefined : data.experienceMin as number | undefined,
        experienceMax: data.experienceMax === "" ? undefined : data.experienceMax as number | undefined,
      }),
    onSuccess() {
      toast.success("Job updated.");
      void qc.invalidateQueries({ queryKey: queryKeys.jobs.all() });
      setEditing(false);
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          setError(field as keyof EditForm, { message });
        });
      } else {
        toast.error(
          error instanceof AppError ? error.message : "Update failed.",
        );
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => jobsApi.delete(jobId!),
    onSuccess() {
      toast.success("Job deleted.");
      void qc.invalidateQueries({ queryKey: queryKeys.jobs.all() });
      navigate("/app/jobs");
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not delete job.",
      );
    },
  });

  // ── JD upload ──────────────────────────────────────────────────────────────

  async function handleJdUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file || !jobId) return;

    try {
      setUploadProgress(0);
      const { uploadUrl } = await jobsApi.getJdUploadUrl(
        jobId,
        file.name,
        file.type || "application/pdf",
      );
      await uploadToS3(uploadUrl, file, setUploadProgress);
      toast.success("Job description uploaded.");
    } catch {
      toast.error("Upload failed. Please try again.");
    } finally {
      setUploadProgress(null);
      e.target.value = "";
    }
  }

  // ── Loading / error states ─────────────────────────────────────────────────

  if (jobLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-48 animate-pulse rounded bg-muted" />
        <div className="h-40 animate-pulse rounded-lg bg-muted" />
      </div>
    );
  }

  if (isError || !job) {
    return (
      <EmptyState
        title="Job not found"
        description="This job may have been deleted or you don't have access."
        action={{ label: "Back to Jobs", onClick: () => navigate("/app/jobs") }}
      />
    );
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  const candidates = candidatesPage?.content ?? [];

  return (
    <div className="space-y-6">
      {/* Header */}
      <PageHeader
        title={job.title}
        description={`Created ${formatDate(job.createdAt)}`}
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate("/app/jobs")}
            >
              <ArrowLeft className="mr-1 h-4 w-4" />
              Back
            </Button>
            {!editing ? (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setEditing(true)}
                >
                  <Pencil className="mr-1 h-4 w-4" />
                  Edit
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => {
                    if (
                      window.confirm(
                        "Delete this job? This cannot be undone.",
                      )
                    ) {
                      deleteMutation.mutate();
                    }
                  }}
                  disabled={deleteMutation.isPending}
                >
                  {deleteMutation.isPending ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Trash2 className="h-4 w-4" />
                  )}
                </Button>
              </>
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
          </div>
        }
      />

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ── Job details card ──────────────────────────────────────────────── */}
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Job Details</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="space-y-4 pt-4">
              {!editing ? (
                /* View mode */
                <>
                  <div className="flex items-center gap-2">
                    <StatusBadge kind="job" status={job.status} />
                  </div>
                  <dl className="grid gap-3 sm:grid-cols-2">
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Department
                      </dt>
                      <dd className="mt-1 text-sm">
                        {job.department ?? "—"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Location Type
                      </dt>
                      <dd className="mt-1 text-sm">
                        {job.locationType ?? "—"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Employment Type
                      </dt>
                      <dd className="mt-1 text-sm">
                        {job.employmentType
                          ? job.employmentType.replace("_", " ").replace(/\b\w/g, (c) => c.toUpperCase())
                          : "—"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Experience
                      </dt>
                      <dd className="mt-1 text-sm">
                        {job.experienceMin != null || job.experienceMax != null
                          ? `${job.experienceMin ?? 0} – ${job.experienceMax ?? "∞"} yrs`
                          : "—"}
                      </dd>
                    </div>
                  </dl>
                  {job.description && (
                    <div>
                      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Description
                      </dt>
                      <dd className="mt-1 whitespace-pre-wrap text-sm">
                        {job.description}
                      </dd>
                    </div>
                  )}
                </>
              ) : (
                /* Edit mode */
                <div className="space-y-4">
                  {/* Status */}
                  <div className="space-y-1.5">
                    <Label>Status</Label>
                    <Select
                      value={currentStatus}
                      onValueChange={(v) =>
                        setValue("status", v as JobStatus, {
                          shouldDirty: true,
                        })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {(
                          ["ACTIVE", "CLOSED", "ARCHIVED"] as JobStatus[]
                        ).map((s) => (
                          <SelectItem key={s} value={s}>
                            {s.charAt(0) + s.slice(1).toLowerCase()}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  {/* Title */}
                  <div className="space-y-1.5">
                    <Label htmlFor="edit-title">Job title *</Label>
                    <Input id="edit-title" {...register("title")} />
                    {errors.title && (
                      <p className="text-xs text-destructive">
                        {errors.title.message}
                      </p>
                    )}
                  </div>

                  {/* Department + Location */}
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label htmlFor="edit-dept">Department</Label>
                      <Input
                        id="edit-dept"
                        {...register("department")}
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label>Location Type</Label>
                      <Select
                        value={watch("locationType") ?? ""}
                        onValueChange={(v) =>
                          setValue("locationType", v as LocationType, {
                            shouldDirty: true,
                          })
                        }
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="Select…" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="REMOTE">Remote</SelectItem>
                          <SelectItem value="ONSITE">On-site</SelectItem>
                          <SelectItem value="HYBRID">Hybrid</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                  </div>

                  {/* Employment Type */}
                  <div className="space-y-1.5">
                    <Label>Employment Type</Label>
                    <Select
                      value={watch("employmentType") ?? ""}
                      onValueChange={(v) =>
                        setValue("employmentType", v as EmploymentType, {
                          shouldDirty: true,
                        })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Select…" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="FULL_TIME">Full-time</SelectItem>
                        <SelectItem value="PART_TIME">Part-time</SelectItem>
                        <SelectItem value="CONTRACT">Contract</SelectItem>
                        <SelectItem value="INTERNSHIP">Internship</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {/* Experience Range */}
                  <div className="grid grid-cols-2 gap-3">
                    <div className="space-y-1.5">
                      <Label htmlFor="edit-expMin">Min Experience (yrs)</Label>
                      <Input
                        id="edit-expMin"
                        type="number"
                        min={0}
                        placeholder="0"
                        {...register("experienceMin")}
                      />
                      {errors.experienceMin && (
                        <p className="text-xs text-destructive">
                          {errors.experienceMin.message}
                        </p>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="edit-expMax">Max Experience (yrs)</Label>
                      <Input
                        id="edit-expMax"
                        type="number"
                        min={0}
                        placeholder="e.g. 5"
                        {...register("experienceMax")}
                      />
                      {errors.experienceMax && (
                        <p className="text-xs text-destructive">
                          {errors.experienceMax.message}
                        </p>
                      )}
                    </div>
                  </div>

                  {/* Description */}
                  <div className="space-y-1.5">
                    <Label htmlFor="edit-desc">Description</Label>
                    <textarea
                      id="edit-desc"
                      rows={5}
                      className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                      {...register("description")}
                    />
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* ── The employer's own questions (§7.5.8) ─────────────────────── */}
          <QuestionBankPanel jobId={jobId!} />

          {/* ── Candidates list ──────────────────────────────────────────────── */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="flex items-center gap-2 text-base">
                <Users className="h-4 w-4" />
                Candidates ({candidatesPage?.totalElements ?? 0})
              </CardTitle>
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  navigate(`/app/candidates?jobId=${jobId}`)
                }
              >
                View all
              </Button>
            </CardHeader>
            <Separator />
            <CardContent className="p-0">
              {candidatesLoading ? (
                <div className="space-y-2 p-4">
                  {[1, 2].map((i) => (
                    <div
                      key={i}
                      className="h-12 animate-pulse rounded bg-muted"
                    />
                  ))}
                </div>
              ) : candidates.length === 0 ? (
                <div className="py-8 text-center text-sm text-muted-foreground">
                  No candidates yet for this job.
                </div>
              ) : (
                <ul className="divide-y">
                  {candidates.slice(0, 5).map((c) => (
                    <li
                      key={c.id}
                      className="flex cursor-pointer items-center justify-between gap-4 px-6 py-3 hover:bg-muted/50"
                      onClick={() =>
                        navigate(`/app/candidates/${c.id}`)
                      }
                    >
                      <div className="min-w-0">
                        <p className="truncate font-medium text-sm">
                          {c.fullName}
                        </p>
                        <p className="truncate text-xs text-muted-foreground">
                          {c.email}
                        </p>
                      </div>
                      <StatusBadge kind="pipeline" status={c.resumeExtractionStatus} />
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </div>

        {/* ── Sidebar: JD upload ────────────────────────────────────────────── */}
        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Job Description File</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <p className="mb-3 text-sm text-muted-foreground">
                Upload a PDF or Word document. The AI uses it to generate
                relevant interview questions.
              </p>

              <label className="block">
                <input
                  type="file"
                  accept=".pdf,.doc,.docx"
                  className="sr-only"
                  onChange={handleJdUpload}
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
                        Upload JD
                      </>
                    )}
                  </span>
                </Button>
              </label>

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

          {/* ── Interview length (§7.2.1) ─────────────────────────────────── */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Interview length</CardTitle>
            </CardHeader>
            <Separator />
            <CardContent className="pt-4">
              <DurationTierSelect
                value={job.durationTier ?? "STANDARD"}
                onChange={(tier) => tierMutation.mutate(tier)}
                disabled={tierMutation.isPending}
              />
            </CardContent>
          </Card>

          {/* Metadata */}
          <Card>
            <CardContent className="pt-4">
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <dt className="text-muted-foreground">Created</dt>
                  <dd>{formatDate(job.createdAt)}</dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-muted-foreground">Last updated</dt>
                  <dd>{formatDate(job.updatedAt)}</dd>
                </div>
              </dl>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
