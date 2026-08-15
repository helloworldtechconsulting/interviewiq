// =============================================================================
// VerifyEmailPage.tsx
//
// User lands here after register.  They enter the 6-digit OTP sent to their
// email.  On success → navigate to /app/dashboard.
// =============================================================================

import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, useLocation } from "react-router-dom";
import { toast } from "sonner";
import { Loader2, Mail } from "lucide-react";

import { authApi } from "@/api/modules/auth";
import { AppError } from "@/api/client";
import { authStore, useAuthUser } from "@/stores/authStore";
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
  otp: z
    .string()
    .length(6, "OTP must be exactly 6 digits")
    .regex(/^\d+$/, "OTP must only contain digits"),
});

type FormData = z.infer<typeof schema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function VerifyEmailPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthUser();

  const state = location.state as { email?: string; slug?: string } | null;
  const email = state?.email ?? user?.email ?? "";
  const slug = state?.slug; // undefined → authApi falls back to VITE_COMPANY_SLUG

  const [resendCooldown, setResendCooldown] = useState(0);
  const cooldownRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Cleanup interval on unmount
  useEffect(() => {
    return () => {
      if (cooldownRef.current) clearInterval(cooldownRef.current);
    };
  }, []);

  function startCooldown() {
    setResendCooldown(60);
    cooldownRef.current = setInterval(() => {
      setResendCooldown((prev) => {
        if (prev <= 1) {
          if (cooldownRef.current) clearInterval(cooldownRef.current);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  }

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const verifyMutation = useMutation({
    mutationFn: (data: FormData) =>
      authApi.verifyEmail({ email, otp: data.otp }, slug),
    onSuccess(data) {
      authStore.getState().setAccessToken(data.accessToken);
      toast.success("Email verified! Welcome aboard.");
      navigate("/app/dashboard", { replace: true });
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          setError(field as keyof FormData, { message });
        });
      } else {
        toast.error(
          error instanceof AppError
            ? error.message
            : "Verification failed. Try again.",
        );
      }
    },
  });

  const resendMutation = useMutation({
    mutationFn: () => authApi.resendVerification({ email }, slug),
    onSuccess() {
      toast.success("A new OTP has been sent to your email.");
      startCooldown();
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not resend OTP.",
      );
    },
  });

  function onSubmit(data: FormData) {
    verifyMutation.mutate(data);
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-primary/10">
          <Mail className="h-6 w-6 text-primary" />
        </div>
        <CardTitle className="text-2xl">Check your email</CardTitle>
        <CardDescription>
          We sent a 6-digit code to{" "}
          <span className="font-medium text-foreground">{email || "your email"}</span>.
          Enter it below to verify your account.
        </CardDescription>
      </CardHeader>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="otp">One-time password</Label>
            <Input
              id="otp"
              type="text"
              inputMode="numeric"
              maxLength={6}
              placeholder="123456"
              autoComplete="one-time-code"
              autoFocus
              className="text-center text-xl tracking-[0.5em]"
              {...register("otp")}
            />
            {errors.otp && (
              <p className="text-xs text-destructive">{errors.otp.message}</p>
            )}
          </div>
        </CardContent>

        <CardFooter className="flex flex-col gap-3">
          <Button
            type="submit"
            className="w-full"
            disabled={verifyMutation.isPending}
          >
            {verifyMutation.isPending && (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            )}
            Verify email
          </Button>

          <Button
            type="button"
            variant="ghost"
            className="w-full text-sm"
            disabled={resendMutation.isPending || resendCooldown > 0}
            onClick={() => resendMutation.mutate()}
          >
            {resendMutation.isPending ? (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            ) : null}
            {resendCooldown > 0
              ? `Resend code in ${resendCooldown}s`
              : "Resend code"}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}
