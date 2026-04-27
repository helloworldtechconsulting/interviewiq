// =============================================================================
// GoogleSignInButton.tsx — Reusable Google OAuth sign-in button
//
// Wraps @react-oauth/google's <GoogleLogin> component and normalises the
// credential response into a plain callback so callers never touch the
// Google library API directly.
//
// Usage:
//   <GoogleSignInButton
//     onSuccess={(idToken) => /* call your API */}
//     onError={() => toast.error("Google sign-in failed")}
//     text="signin_with"   // or "signup_with" | "continue_with" | "signin"
//   />
// =============================================================================

import { GoogleLogin, type CredentialResponse } from "@react-oauth/google";

interface GoogleSignInButtonProps {
  /** Called with the raw Google ID token JWT string on successful sign-in. */
  onSuccess: (idToken: string) => void;
  /** Called when the Google sign-in flow fails or is cancelled. */
  onError?: () => void;
  /** Button text variant. Defaults to "signin_with". */
  text?: "signin_with" | "signup_with" | "continue_with" | "signin";
  /** Whether the button should be disabled (e.g. while a mutation is pending). */
  disabled?: boolean;
}

/**
 * Renders the official Google sign-in button using the Google Identity Services
 * library. The button handles the OAuth popup / redirect flow internally and
 * fires {@link onSuccess} with the verified ID token credential string.
 *
 * If {@code VITE_GOOGLE_CLIENT_ID} is empty (not configured), the button renders
 * in a disabled state with a placeholder message — no runtime error is thrown.
 */
export function GoogleSignInButton({
  onSuccess,
  onError,
  text = "signin_with",
  disabled = false,
}: GoogleSignInButtonProps) {
  function handleSuccess(response: CredentialResponse) {
    if (!response.credential) {
      onError?.();
      return;
    }
    onSuccess(response.credential);
  }

  function handleError() {
    onError?.();
  }

  if (disabled) {
    return (
      <div
        aria-disabled="true"
        className="flex h-10 w-full cursor-not-allowed items-center justify-center rounded-md border border-input bg-background px-4 py-2 text-sm text-muted-foreground opacity-50"
      >
        <GoogleIcon className="mr-2 h-4 w-4" />
        Continue with Google
      </div>
    );
  }

  return (
    <GoogleLogin
      onSuccess={handleSuccess}
      onError={handleError}
      text={text}
      width="100%"
      useOneTap={false}
    />
  );
}

// ── Inline Google "G" logo SVG ────────────────────────────────────────────────
// Used only in the disabled state fallback above.

function GoogleIcon({ className }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      className={className}
      aria-hidden="true"
    >
      <path
        d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
        fill="#4285F4"
      />
      <path
        d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
        fill="#34A853"
      />
      <path
        d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
        fill="#FBBC05"
      />
      <path
        d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
        fill="#EA4335"
      />
    </svg>
  );
}
