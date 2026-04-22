// =============================================================================
// LoginPage.tsx — Employer login with email + password
// =============================================================================

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, Link, useLocation } from "react-router-dom";
import { toast } from "sonner";
import { Loader2 } from "lucide-react";

import { authApi } from "@/api/modules/auth";
import { authStore } from "@/stores/authStore";
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

// ── Zod schema ────────────────────────────────────────────────────────────────

const schema = z.object({
  email: z.string().email("Invalid email address"),
  password: z.string().min(1, "Password is required"),
});

type FormData = z.infer<typeof schema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  // Redirect to where the user originally tried to go (or dashboard)
  const from =
    (location.state as { from?: { pathname: string } } | null)?.from
      ?.pathname ?? "/app/dashboard";

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const mutation = useMutation({
    // Explicit wrapper — never pass authApi.login directly as mutationFn.
    // Multi-parameter function references can receive React Query's internal
    // context object as a second argument, corrupting the optional `slug` param.
    mutationFn: (credentials: FormData) => authApi.login(credentials),
    onSuccess(data) {
      authStore.getState().setTokens(data.accessToken, data.refreshToken);
      navigate(from, { replace: true });
    },
    onError(error) {
      if (error instanceof AppError) {
        if (error.fieldErrors) {
          Object.entries(error.fieldErrors).forEach(([field, message]) => {
            setError(field as keyof FormData, { message });
          });
        } else if (error.status === 401) {
          toast.error("Invalid email or password.");
        } else if (error.status === 403) {
          // Email not verified — redirect, carrying the email so the OTP form
          // knows which address to display and resend to
          toast.info("Please verify your email before signing in.");
          navigate("/verify-email", {
            state: { email: mutation.variables?.email ?? "" },
            replace: true,
          });
        } else {
          toast.error(error.message);
        }
      } else {
        toast.error("Login failed. Please try again.");
      }
    },
  });

  function onSubmit(data: FormData) {
    mutation.mutate(data);
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <CardTitle className="text-2xl">Welcome back</CardTitle>
        <CardDescription>Sign in to your InterviewIQ account</CardDescription>
      </CardHeader>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4">
          {/* Email */}
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

          {/* Password */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <Label htmlFor="password">Password</Label>
              <Link
                to="/forgot-password"
                className="text-xs text-muted-foreground underline-offset-4 hover:underline"
              >
                Forgot password?
              </Link>
            </div>
            <Input
              id="password"
              type="password"
              placeholder="Your password"
              autoComplete="current-password"
              {...register("password")}
            />
            {errors.password && (
              <p className="text-xs text-destructive">
                {errors.password.message}
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
            Sign in
          </Button>

          <p className="text-center text-sm text-muted-foreground">
            Don&apos;t have an account?{" "}
            <Link
              to="/onboarding"
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              Get started free
            </Link>
          </p>
        </CardFooter>
      </form>
    </Card>
  );
}
