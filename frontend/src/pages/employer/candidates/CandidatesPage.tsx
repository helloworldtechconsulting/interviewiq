// =============================================================================
// CandidatesPage.tsx — All candidates with job filter + add candidate dialog
// =============================================================================

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import {
  Plus,
  Users,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Search,
  Mail,
  Phone,
} from "lucide-react";

import { candidatesApi } from "@/api/modules/candidates";
import { jobsApi } from "@/api/modules/jobs";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";
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
import { formatDate } from "@/lib/utils";

const PAGE_SIZE = 12;

// ── Create candidate schema ───────────────────────────────────────────────────

const addSchema = z.object({
  jobOpeningId: z.string().min(1, "Please select a job"),
  fullName: z.string().min(2, "Name must be at least 2 characters"),
  email: z.string().email("Invalid email"),
  phone: z.string().optional(),
});

type AddForm = z.infer<typeof addSchema>;

// ── Add Candidate Dialog ──────────────────────────────────────────────────────

interface AddCandidateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultJobId?: string;
}

function AddCandidateDialog({
  open,
  onOpenChange,
  defaultJobId,
}: AddCandidateDialogProps) {
  const qc = useQueryClient();
  const navigate = useNavigate();

  const { data: jobsData } = useQuery({
    queryKey: queryKeys.jobs.list(),
    queryFn: () => jobsApi.list({ status: "OPEN", size: 100 }),
    enabled: open,
  });

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    setError,
    formState: { errors },
  } = useForm<AddForm>({
    resolver: zodResolver(addSchema),
    defaultValues: { jobOpeningId: defaultJobId ?? "" },
  });

  const selectedJobId = watch("jobOpeningId");

  const mutation = useMutation({
    mutationFn: candidatesApi.create,
    onSuccess(candidate) {
      toast.success("Candidate added.");
      void qc.invalidateQueries({ queryKey: queryKeys.candidates.all() });
      reset();
      onOpenChange(false);
      navigate(`/app/candidates/${candidate.id}`);
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          setError(field as keyof AddForm, { message });
        });
      } else {
        toast.error(
          error instanceof AppError ? error.message : "Failed to add candidate.",
        );
      }
    },
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!mutation.isPending) {
          reset({ jobOpeningId: defaultJobId ?? "" });
          onOpenChange(v);
        }
      }}
    >
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add candidate</DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit((d) => mutation.mutate(d))} noValidate>
          <div className="space-y-4 py-2">
            {/* Job selection */}
            <div className="space-y-1.5">
              <Label>Job *</Label>
              <Select
                value={selectedJobId}
                onValueChange={(v) =>
                  setValue("jobOpeningId", v, { shouldValidate: true })
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="Select a job…" />
                </SelectTrigger>
                <SelectContent>
                  {(jobsData?.content ?? []).map((j) => (
                    <SelectItem key={j.id} value={j.id}>
                      {j.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {errors.jobOpeningId && (
                <p className="text-xs text-destructive">
                  {errors.jobOpeningId.message}
                </p>
              )}
            </div>

            {/* Name */}
            <div className="space-y-1.5">
              <Label htmlFor="fullName">Full name *</Label>
              <Input
                id="fullName"
                placeholder="Anita Sharma"
                autoFocus
                {...register("fullName")}
              />
              {errors.fullName && (
                <p className="text-xs text-destructive">
                  {errors.fullName.message}
                </p>
              )}
            </div>

            {/* Email */}
            <div className="space-y-1.5">
              <Label htmlFor="email">Email *</Label>
              <Input
                id="email"
                type="email"
                placeholder="anita@example.com"
                {...register("email")}
              />
              {errors.email && (
                <p className="text-xs text-destructive">
                  {errors.email.message}
                </p>
              )}
            </div>

            {/* Phone */}
            <div className="space-y-1.5">
              <Label htmlFor="phone">Phone</Label>
              <Input
                id="phone"
                type="tel"
                placeholder="+91 98765 43210"
                {...register("phone")}
              />
            </div>
          </div>

          <DialogFooter className="mt-4">
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
              Add candidate
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export function CandidatesPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const defaultJobId = searchParams.get("jobId") ?? undefined;

  const [page, setPage] = useState(0);
  const [jobIdFilter, setJobIdFilter] = useState(defaultJobId ?? "ALL");
  const [search, setSearch] = useState("");
  const [addOpen, setAddOpen] = useState(false);

  // Fetch all open jobs for the filter dropdown
  const { data: jobsData } = useQuery({
    queryKey: queryKeys.jobs.list(),
    queryFn: () => jobsApi.list({ status: "OPEN", size: 100 }),
  });

  const queryJobOpeningId = jobIdFilter === "ALL" ? undefined : jobIdFilter;

  const { data, isLoading } = useQuery({
    queryKey: queryKeys.candidates.list({
      page,
      jobOpeningId: queryJobOpeningId,
    }),
    queryFn: () =>
      candidatesApi.list({
        page,
        size: PAGE_SIZE,
        jobOpeningId: queryJobOpeningId,
      }),
  });

  const candidates = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const filtered = search.trim()
    ? candidates.filter(
        (c) =>
          c.fullName.toLowerCase().includes(search.toLowerCase()) ||
          c.email.toLowerCase().includes(search.toLowerCase()),
      )
    : candidates;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Candidates"
        description="Track every candidate through your hiring pipeline."
        actions={
          <Button onClick={() => setAddOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            Add Candidate
          </Button>
        }
      />

      {/* ── Filters ──────────────────────────────────────────────────────────── */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search candidates…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>

        {/* Job filter */}
        <Select
          value={jobIdFilter}
          onValueChange={(v) => {
            setJobIdFilter(v);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-full sm:w-48">
            <SelectValue placeholder="All jobs" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All jobs</SelectItem>
            {(jobsData?.content ?? []).map((j) => (
              <SelectItem key={j.id} value={j.id}>
                {j.title}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* ── Candidate grid ───────────────────────────────────────────────────── */}
      {isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <div key={i} className="h-32 animate-pulse rounded-lg bg-muted" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Users}
          title="No candidates found"
          description={
            search
              ? "No candidates match your search."
              : "Add your first candidate to get started."
          }
          action={
            !search
              ? { label: "Add Candidate", onClick: () => setAddOpen(true) }
              : undefined
          }
        />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((c) => (
            <Card
              key={c.id}
              className="cursor-pointer transition-shadow hover:shadow-md"
              onClick={() => navigate(`/app/candidates/${c.id}`)}
            >
              <CardContent className="p-5">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary">
                    {c.fullName.charAt(0).toUpperCase()}
                  </div>
                  <StatusBadge kind="pipeline" status={c.resumeExtractionStatus} />
                </div>
                <div className="mt-3">
                  <p className="font-semibold">{c.fullName}</p>
                  <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                    Job {c.jobOpeningId.slice(0, 8)}…
                  </p>
                  <div className="mt-2 space-y-1">
                    <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                      <Mail className="h-3 w-3" />
                      <span className="truncate">{c.email}</span>
                    </p>
                    {c.phone && (
                      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <Phone className="h-3 w-3" />
                        {c.phone}
                      </p>
                    )}
                  </div>
                </div>
                <p className="mt-3 text-right text-xs text-muted-foreground">
                  Added {formatDate(c.createdAt)}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* ── Pagination ───────────────────────────────────────────────────────── */}
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

      <AddCandidateDialog
        open={addOpen}
        onOpenChange={setAddOpen}
        defaultJobId={defaultJobId}
      />
    </div>
  );
}
