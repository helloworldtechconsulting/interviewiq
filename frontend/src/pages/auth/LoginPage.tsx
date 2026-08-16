import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, Link, useLocation } from "react-router-dom";
import { toast } from "sonner";
import { Eye, EyeOff, Loader2 } from "lucide-react";

import { authApi } from "@/api/modules/auth";
import { authStore } from "@/stores/authStore";
import { AppError } from "@/api/client";
import { GoogleSignInButton } from "@/components/auth/GoogleSignInButton";
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

  const [showPassword, setShowPassword] = useState(false);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  const mutation = useMutation({
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
          toast.info("Please verify your email before signing in.");
          navigate("/verify-email", {
            state: { email: mutation.variables?.email ?? "" },
            replace: true,
          });
        } else if (error.status === 429) {
          const retryAfter = error.retryAfterSeconds;
          const msg = retryAfter
            ? `Too many failed attempts. Try again in ${retryAfter} seconds.`
            : "Too many failed attempts. Please wait before trying again.";
          toast.error(msg);
        } else {
          toast.error(error.message);
        }
      } else {
        toast.error("Login failed. Please try again.");
      }
    },
  });

  const googleMutation = useMutation({
    mutationFn: (idToken: string) => authApi.googleLogin(idToken),
    onSuccess(data) {
      authStore.getState().setTokens(data.accessToken, data.refreshToken);
      navigate(from, { replace: true });
    },
    onError(error) {
      if (error instanceof AppError && error.status === 429) {
        toast.error("Too many requests. Please wait before trying again.");
      } else {
        toast.error("Google sign-in failed. Please try again.");
      }
    },
  });

  function onSubmit(data: FormData) {
    mutation.mutate(data);
  }

  const isLoading = mutation.isPending || googleMutation.isPending;

  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <CardTitle className="text-2xl">Welcome back</CardTitle>
        <CardDescription>Sign in to your InterviewIQ account</CardDescription>
      </CardHeader>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4">
          {/* Google sign-in */}
          <GoogleSignInButton
            text="signin_with"
            onSuccess={(idToken) => googleMutation.mutate(idToken)}
            onError={() => toast.error("Google sign-in failed. Please try again.")}
            disabled={isLoading}
          />

          {/* Divider */}
          <div className="relative">
            <div className="absolute inset-0 flex items-center">
              <span className="w-full border-t" />
            </div>
            <div className="relative flex justify-center text-xs uppercase">
              <span className="bg-background px-2 text-muted-foreground">
                or continue with email
              </span>
            </div>
          </div>

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

          {/* Password with eye toggle */}
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
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? "text" : "password"}
                placeholder="Your password"
                autoComplete="current-password"
                className="pr-10"
                {...register("password")}
              />
              <button
                type="button"
                aria-label={showPassword ? "Hide password" : "Show password"}
                onClick={() => setShowPassword((v) => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground focus:outline-none"
                tabIndex={-1}
              >
                {showPassword ? (
                  <EyeOff className="h-4 w-4" />
                ) : (
                  <Eye className="h-4 w-4" />
                )}
              </button>
            </div>
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
            disabled={isLoading}
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
