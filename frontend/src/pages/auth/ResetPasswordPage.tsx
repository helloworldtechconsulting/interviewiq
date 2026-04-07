// =============================================================================
// ResetPasswordPage.tsx
//
// User lands here via the reset link: /reset-password?token=<jwt>
// On success → navigate to /login.
// =============================================================================

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, useSearchParams, Link } from "react-router-dom";
import { toast } from "sonner";
import { Loader2, AlertTriangle } from "lucide-react";

import { authApi } from "@/api/modules/auth";
import { AppError } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

// ── Schema ────────────────────────────────────────────────────────────────────

const schema = z
  .object({
    newPassword: z
      .string()
      .min(8, "Password must be at least 8 characters")
      .regex(/[A-Z]/, "Must contain at least one uppercase letter")
      .regex(/[0-9]/, "Must contain at least one number"),
    confirmPassword: z.string(),
  })
  .refine((d) => d.newPassword === d.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
  });

type FormData = z.infer<typeof schema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token");

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const mutation = useMutation({
    mutationFn: (data: FormData) =>
      authApi.resetPassword({ token: token!, newPassword: data.newPassword }),
    onSuccess() {
      toast.success("Password updated. You can now sign in.");
      navigate("/login", { replace: true });
    },
    onError(error) {
      if (error instanceof AppError) {
        if (error.fieldErrors) {
          Object.entries(error.fieldErrors).forEach(([field, message]) => {
            setError(field as keyof FormData, { message });
          });
        } else if (error.status === 400 || error.status === 401) {
          toast.error("This reset link has expired or is invalid.");
        } else {
          toast.error(error.message);
        }
      } else {
        toast.error("Something went wrong. Please try again.");
      }
    },
  });

  function onSubmit(data: FormData) {
    mutation.mutate(data);
  }

  // ── Guard: no token in URL ─────────────────────────────────────────────────
  if (!token) {
    return (
      <Card className="w-full max-w-md text-center">
        <CardHeader>
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-destructive/10">
            <AlertTriangle className="h-6 w-6 text-destructive" />
          </div>
          <CardTitle>Invalid reset link</CardTitle>
          <CardDescription>
            This link is missing the reset token. Please request a new one.
          </CardDescription>
        </CardHeader>
        <CardFooter className="justify-center">
          <Link
            to="/forgot-password"
            className="text-sm font-medium text-primary underline-offset-4 hover:underline"
          >
            Request new link
          </Link>
        </CardFooter>
      </Card>
    );
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <CardTitle className="text-2xl">Set new password</CardTitle>
        <CardDescription>
          Choose a strong password for your account.
        </CardDescription>
      </CardHeader>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="newPassword">New password</Label>
            <Input
              id="newPassword"
              type="password"
              placeholder="Min. 8 characters"
              autoComplete="new-password"
              autoFocus
              {...register("newPassword")}
            />
            {errors.newPassword && (
              <p className="text-xs text-destructive">
                {errors.newPassword.message}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="confirmPassword">Confirm new password</Label>
            <Input
              id="confirmPassword"
              type="password"
              placeholder="Repeat password"
              autoComplete="new-password"
              {...register("confirmPassword")}
            />
            {errors.confirmPassword && (
              <p className="text-xs text-destructive">
                {errors.confirmPassword.message}
              </p>
            )}
          </div>
        </CardContent>

        <CardFooter className="flex flex-col gap-3">
          <Button
            type="submit"
            className="w-full"
            disabled={mutation.isPending}
          >
            {mutation.isPending && (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            )}
            Update password
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}
