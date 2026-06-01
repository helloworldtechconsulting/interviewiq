// =============================================================================
// OnboardingPage.tsx — Register a new company + admin account
//
// Two registration paths:
//   1. Google OAuth — one click, company name required, no OTP verification
//   2. Email + password — full form, email OTP verification required
//
// For email+password: calls POST /api/v1/companies/register (companiesApi.onboard)
// which atomically creates: company + first ADMIN user + empty wallet.
// On success the backend returns { slug, email }. The slug is stored in
// navigation state so VerifyEmailPage can call /api/v1/{slug}/auth/verify-email
// with the correct company context.
//
// For Google: calls POST /api/v1/auth/google/register — no OTP step needed
// because Google has already verified the email. Redirects to dashboard directly.
// =============================================================================

import { useState, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, Link } from "react-router-dom";
import { toast } from "sonner";
import { Loader2 } from "lucide-react";

import { companiesApi } from "@/api/modules/companies";
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

const schema = z
  .object({
    companyName: z
      .string()
      .min(2, "Company name must be at least 2 characters"),
    adminName: z.string().min(2, "Full name must be at least 2 characters"),
    email: z.string().email("Invalid email address"),
    password: z
      .string()
      .min(8, "Password must be at least 8 characters")
      .regex(/[A-Z]/, "Must contain at least one uppercase letter")
      .regex(/[0-9]/, "Must contain at least one number"),
    confirmPassword: z.string(),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
  });

type FormData = z.infer<typeof schema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function OnboardingPage() {
  const navigate = useNavigate();

  // When a Google credential is received but we still need a company name,
  // we temporarily hold the idToken here and show a mini-form.
  const [googleIdToken, setGoogleIdToken] = useState<string | null>(null);
  const [googleCompanyName, setGoogleCompanyName] = useState("");

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) });

  // ── Email + password registration ─────────────────────────────────────────

  const mutation = useMutation({
    mutationFn: (data: FormData) =>
      companiesApi.onboard({
        companyName: data.companyName,
        adminName: data.adminName,
        email: data.email,
        password: data.password,
      }),
    onSuccess(result) {
      toast.success(
        "Account created! Please check your email for the verification code.",
      );
      navigate("/verify-email", {
        replace: true,
        state: { email: result.email, slug: result.slug },
      });
    },
    onError(error) {
      if (error instanceof AppError) {
        if (error.fieldErrors) {
          Object.entries(error.fieldErrors).forEach(([field, message]) => {
            const formField =
              field === "adminName" ? "adminName" : (field as keyof FormData);
            setError(formField, { message: message as string });
          });
        } else {
          toast.error(error.message);
        }
      } else {
        toast.error("Registration failed. Please try again.");
      }
    },
  });

  // ── Google registration ────────────────────────────────────────────────────

  const googleRegisterMutation = useMutation({
    mutationFn: ({ idToken, companyName }: { idToken: string; companyName: string }) =>
      authApi.googleRegister(idToken, companyName),
    onSuccess(data) {
      authStore.getState().setTokens(data.accessToken, data.refreshToken);
      toast.success("Account created! Welcome to InterviewIQ.");
      navigate("/app/dashboard", { replace: true });
    },
    onError(error) {
      if (error instanceof AppError) {
        toast.error(error.message);
      } else {
        toast.error("Google registration failed. Please try again.");
      }
      // Reset Google flow so user can retry
      setGoogleIdToken(null);
      setGoogleCompanyName("");
    },
  });

  function onSubmit(data: FormData) {
    mutation.mutate(data);
  }

  function handleGoogleCredential(idToken: string) {
    // We have the token but still need a company name
    setGoogleIdToken(idToken);
  }

  function submitGoogleRegister(e: FormEvent) {
    e.preventDefault();
    if (!googleIdToken || !googleCompanyName.trim()) return;
    googleRegisterMutation.mutate({
      idToken: googleIdToken,
      companyName: googleCompanyName.trim(),
    });
  }

  const isLoading = mutation.isPending || googleRegisterMutation.isPending;

  // ── Google company-name collection sub-form ────────────────────────────────

  if (googleIdToken) {
    return (
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">One more thing</CardTitle>
          <CardDescription>
            What&apos;s your company name?
          </CardDescription>
        </CardHeader>
        <form onSubmit={submitGoogleRegister}>
          <CardContent className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="googleCompanyName">Company name</Label>
              <Input
                id="googleCompanyName"
                placeholder="Acme Corp"
                autoComplete="organization"
                autoFocus
                value={googleCompanyName}
                onChange={(e) => setGoogleCompanyName(e.target.value)}
              />
            </div>
          </CardContent>
          <CardFooter className="flex flex-col gap-3">
            <Button
              type="submit"
              className="w-full"
              disabled={!googleCompanyName.trim() || isLoading}
            >
              {googleRegisterMutation.isPending && (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              )}
              Create account
            </Button>
            <button
              type="button"
              className="text-sm text-muted-foreground underline-offset-4 hover:underline"
              onClick={() => { setGoogleIdToken(null); setGoogleCompanyName(""); }}
            >
              Back
            </button>
          </CardFooter>
        </form>
      </Card>
    );
  }

  // ── Main registration form ─────────────────────────────────────────────────

  return (
    <Card className="w-full max-w-md">
      <CardHeader className="text-center">
        <CardTitle className="text-2xl">Create your account</CardTitle>
        <CardDescription>
          Start hiring smarter with AI-powered interviews
        </CardDescription>
      </CardHeader>

      <CardContent className="pb-0">
        {/* Google sign-up */}
        <div className="mb-4">
          <GoogleSignInButton
            text="signup_with"
            onSuccess={handleGoogleCredential}
            onError={() => toast.error("Google sign-up failed. Please try again.")}
            disabled={isLoading}
          />
        </div>

        {/* Divider */}
        <div className="relative mb-4">
          <div className="absolute inset-0 flex items-center">
            <span className="w-full border-t" />
          </div>
          <div className="relative flex justify-center text-xs uppercase">
            <span className="bg-background px-2 text-muted-foreground">
              or register with email
            </span>
          </div>
        </div>
      </CardContent>

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-4 pt-0">
          {/* Company name */}
          <div className="space-y-1.5">
            <Label htmlFor="companyName">Company name</Label>
            <Input
              id="companyName"
              placeholder="Acme Corp"
              autoComplete="organization"
              {...register("companyName")}
            />
            {errors.companyName && (
              <p className="text-xs text-destructive">
                {errors.companyName.message}
              </p>
            )}
          </div>

          {/* Full name */}
          <div className="space-y-1.5">
            <Label htmlFor="adminName">Your full name</Label>
            <Input
              id="adminName"
              placeholder="Ravi Kumar"
              autoComplete="name"
              {...register("adminName")}
            />
            {errors.adminName && (
              <p className="text-xs text-destructive">
                {errors.adminName.message}
              </p>
            )}
          </div>

          {/* Email */}
          <div className="space-y-1.5">
            <Label htmlFor="email">Work email</Label>
            <Input
              id="email"
              type="email"
              placeholder="ravi@acmecorp.com"
              autoComplete="email"
              {...register("email")}
            />
            {errors.email && (
              <p className="text-xs text-destructive">{errors.email.message}</p>
            )}
          </div>

          {/* Password */}
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              placeholder="Min. 8 characters"
              autoComplete="new-password"
              {...register("password")}
            />
            {errors.password && (
              <p className="text-xs text-destructive">
                {errors.password.message}
              </p>
            )}
          </div>

          {/* Confirm password */}
          <div className="space-y-1.5">
            <Label htmlFor="confirmPassword">Confirm password</Label>
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
            disabled={isLoading}
          >
            {mutation.isPending && (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            )}
            Create account
          </Button>

          <p className="text-center text-sm text-muted-foreground">
            Already have an account?{" "}
            <Link
              to="/login"
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              Sign in
            </Link>
          </p>
        </CardFooter>
      </form>
    </Card>
  );
}
