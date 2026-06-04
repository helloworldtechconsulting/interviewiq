// =============================================================================
// TeamPage.tsx — ADMIN only
// List team members, invite new ones, toggle active/role
// =============================================================================

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import {
  UserPlus,
  UsersRound,
  ShieldCheck,
  Eye,
  Loader2,
  ToggleLeft,
  ToggleRight,
} from "lucide-react";

import { teamApi } from "@/api/modules/team";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import type { UserRole } from "@/types";
import { useAuthUser } from "@/stores/authStore";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
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
import { Badge } from "@/components/ui/badge";
import { cn, formatDate, initials } from "@/lib/utils";

// ── Invite schema ─────────────────────────────────────────────────────────────

const inviteSchema = z.object({
  fullName: z.string().min(2, "Name must be at least 2 characters"),
  email: z.string().email("Invalid email address"),
  role: z.enum(["ADMIN", "RECRUITER", "VIEWER"]),
});

type InviteForm = z.infer<typeof inviteSchema>;

// ── Role badge ────────────────────────────────────────────────────────────────

const ROLE_META: Record<
  UserRole,
  { label: string; icon: React.ReactNode; colour: string }
> = {
  ADMIN: {
    label: "Admin",
    icon: <ShieldCheck className="h-3 w-3" />,
    colour: "bg-purple-100 text-purple-700",
  },
  RECRUITER: {
    label: "Recruiter",
    icon: <UsersRound className="h-3 w-3" />,
    colour: "bg-blue-100 text-blue-700",
  },
  VIEWER: {
    label: "Viewer",
    icon: <Eye className="h-3 w-3" />,
    colour: "bg-gray-100 text-gray-700",
  },
  SUPER_ADMIN: {
    label: "Super Admin",
    icon: <ShieldCheck className="h-3 w-3" />,
    colour: "bg-red-100 text-red-700",
  },
};

function RoleBadge({ role }: { role: UserRole }) {
  const { label, icon, colour } = ROLE_META[role] ?? ROLE_META.VIEWER;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
        colour,
      )}
    >
      {icon}
      {label}
    </span>
  );
}

// ── Invite Dialog ─────────────────────────────────────────────────────────────

interface InviteDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function InviteDialog({ open, onOpenChange }: InviteDialogProps) {
  const qc = useQueryClient();

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    setError,
    formState: { errors },
  } = useForm<InviteForm>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { role: "RECRUITER" },
  });

  const role = watch("role");

  const mutation = useMutation({
    mutationFn: teamApi.invite,
    onSuccess() {
      toast.success(
        "Invitation sent! The new member will receive an email with their temporary password.",
      );
      void qc.invalidateQueries({ queryKey: queryKeys.team.all() });
      reset();
      onOpenChange(false);
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          setError(field as keyof InviteForm, { message });
        });
      } else {
        toast.error(
          error instanceof AppError ? error.message : "Could not send invite.",
        );
      }
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
          <DialogTitle>Invite team member</DialogTitle>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          A temporary password will be emailed to the new member. They can reset
          it after first login.
        </p>

        <form
          onSubmit={handleSubmit((d) => mutation.mutate(d))}
          noValidate
          className="space-y-4"
        >
          <div className="space-y-1.5">
            <Label htmlFor="fullName">Full name *</Label>
            <Input
              id="fullName"
              placeholder="Priya Singh"
              autoFocus
              {...register("fullName")}
            />
            {errors.fullName && (
              <p className="text-xs text-destructive">
                {errors.fullName.message}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="email">Work email *</Label>
            <Input
              id="email"
              type="email"
              placeholder="priya@yourcompany.com"
              {...register("email")}
            />
            {errors.email && (
              <p className="text-xs text-destructive">{errors.email.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label>Role *</Label>
            <Select
              value={role}
              onValueChange={(v) =>
                  setValue("role", v as "ADMIN" | "RECRUITER" | "VIEWER", { shouldValidate: true })
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ADMIN">
                  Admin — full access, can manage team
                </SelectItem>
                <SelectItem value="RECRUITER">
                  Recruiter — manage jobs and candidates
                </SelectItem>
                <SelectItem value="VIEWER">
                  Viewer — read-only access
                </SelectItem>
              </SelectContent>
            </Select>
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
              Send Invite
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export function TeamPage() {
  const qc = useQueryClient();
  const currentUser = useAuthUser();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  const { data: members, isLoading } = useQuery({
    queryKey: queryKeys.team.list(),
    queryFn: teamApi.list,
  });

  const updateMutation = useMutation({
    mutationFn: ({
      userId,
      patch,
    }: {
      userId: string;
      patch: { role?: UserRole; active?: boolean };
    }) => teamApi.updateMember(userId, patch),
    onMutate({ userId }) {
      setUpdatingId(userId);
    },
    onSuccess() {
      void qc.invalidateQueries({ queryKey: queryKeys.team.all() });
      toast.success("Member updated.");
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Update failed.",
      );
    },
    onSettled() {
      setUpdatingId(null);
    },
  });

  return (
    <div className="space-y-6">
      <PageHeader
        title="Team"
        description="Invite colleagues and manage their access levels."
        actions={
          <Button onClick={() => setInviteOpen(true)}>
            <UserPlus className="mr-2 h-4 w-4" />
            Invite Member
          </Button>
        }
      />

      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-20 animate-pulse rounded-lg bg-muted" />
          ))}
        </div>
      ) : !members || members.length === 0 ? (
        <EmptyState
          icon={UsersRound}
          title="No team members yet"
          description="Invite colleagues to collaborate on your hiring pipeline."
          action={{
            label: "Invite Member",
            onClick: () => setInviteOpen(true),
          }}
        />
      ) : (
        <div className="space-y-3">
          {members.map((m) => {
            const isSelf = m.id === currentUser?.id;
            const isUpdating = updatingId === m.id;

            return (
              <Card key={m.id}>
                <CardContent className="flex items-center gap-4 p-5">
                  {/* Avatar */}
                  <Avatar className="h-10 w-10 shrink-0">
                    <AvatarFallback className="text-sm font-semibold">
                      {initials(m.fullName)}
                    </AvatarFallback>
                  </Avatar>

                  {/* Info */}
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="font-semibold">{m.fullName}</p>
                      {isSelf && (
                        <Badge variant="secondary" className="text-xs">
                          You
                        </Badge>
                      )}
                      {!m.active && (
                        <Badge variant="destructive" className="text-xs">
                          Inactive
                        </Badge>
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground">{m.email}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      Joined {formatDate(m.createdAt)}
                    </p>
                  </div>

                  {/* Role + active controls */}
                  <div className="flex shrink-0 items-center gap-3">
                    <RoleBadge role={m.role} />

                    {!isSelf && (
                      <>
                        {/* Role selector */}
                        <Select
                          value={m.role}
                          onValueChange={(v) =>
                            updateMutation.mutate({
                              userId: m.id,
                              patch: { role: v as UserRole },
                            })
                          }
                          disabled={isUpdating}
                        >
                          <SelectTrigger className="h-8 w-32 text-xs">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="ADMIN">Admin</SelectItem>
                            <SelectItem value="RECRUITER">Recruiter</SelectItem>
                            <SelectItem value="VIEWER">Viewer</SelectItem>
                          </SelectContent>
                        </Select>

                        {/* Active toggle */}
                        <button
                          type="button"
                          disabled={isUpdating}
                          onClick={() =>
                            updateMutation.mutate({
                              userId: m.id,
                              patch: { active: !m.active },
                            })
                          }
                          className="text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
                          title={m.active ? "Deactivate member" : "Activate member"}
                        >
                          {isUpdating ? (
                            <Loader2 className="h-5 w-5 animate-spin" />
                          ) : m.active ? (
                            <ToggleRight className="h-6 w-6 text-primary" />
                          ) : (
                            <ToggleLeft className="h-6 w-6" />
                          )}
                        </button>
                      </>
                    )}
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <InviteDialog open={inviteOpen} onOpenChange={setInviteOpen} />
    </div>
  );
}
