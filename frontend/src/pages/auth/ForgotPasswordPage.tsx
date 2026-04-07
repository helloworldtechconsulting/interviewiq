// =============================================================================
// ForgotPasswordPage.tsx
//
// User enters their email → backend sends a reset link.
// Shows a success state after submission (no redirect).
// =============================================================================

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { Loader2, CheckCircle2 } from "lucide-react";

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

const schema = z.object({
  email: z.string().email("Invalid email address"),
});

type FormData = z.infer<typeof schema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function ForgotPasswordPage() {
  const [submitted, setSubmitted] = useState(false);
  const [submittedEmail, setSubmittedEmail] = useState("");

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const mutation = useMutation({
    mutationFn: authApi.forgotPassword,
    onSuccess(_, variables) {
      setSubmittedEmail(variables.email);
      setSubmitted(true);
    },
    onError(error) {
      toast.error(
        error instanceof AppError
          ? error.message
          : "Something went wrong. Please try again.",
      );
    },
  });

  function onSubmit(data: FormData) {
    mutation.mutate(data);
  }

  // ── Success state ──────────────────────────────────────────────────────────
  if (submitted) {
    return (
      <Card className="w-full max-w-md text-center">
        <CardHeader>
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-green-100">
            <CheckCircle2 className="h-6 w-6 text-green-600" />
          </div>
          <CardTitle className="text-2xl">Check your inbox</CardTitle>
          <CardDescription>
            We&apos;ve sent a password reset link to{" "}
            <span className="font-medium text-foreground">{submittedEmail}</span>.
            The link expires in 30 minutes.
          </CardDescription>
        </CardHeader>
        <CardFooter className="justify-center">
          <Link
            to="/login"
            className="text-sm font-medium text-primary underline-offset-4 hover:underline"
          >
            Back to sign in
          </Link>
        </CardFooter>
      </Card>
    );
  }

  // ── Form ──────────────────────────────────────────────────────────────────
  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <CardTitle className="text-2xl">Reset your password</CardTitle>
        <CardDescription>
          Enter the email associated with your account and we&apos;ll send you a
          reset link.
        </CardDescription>
      </CardHeader>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              type="email"
              placeholder="you@company.com"
              autoComplete="email"
              autoFocus
              {...register("email")}
            />
            {errors.email && (
              <p className="text-xs text-destructive">{errors.email.message}</p>
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
            Send reset link
          </Button>

          <Link
            to="/login"
            className="text-sm text-muted-foreground underline-offset-4 hover:underline"
          >
            Back to sign in
          </Link>
        </CardFooter>
      </form>
    </Card>
  );
}
