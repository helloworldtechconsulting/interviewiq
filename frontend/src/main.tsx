import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { GoogleOAuthProvider } from "@react-oauth/google";
import { Toaster } from "sonner";

import "./index.css";
import { router } from "@/router";
import { queryClient } from "@/lib/queryClient";
import { AuthProvider } from "@/components/common/AuthProvider";

// No storage hydration step: the refresh token lives in an HTTP-only cookie
// this code cannot read (PRD v2.1 §7.1.1). AuthProvider asks the server
// instead, and the browser attaches the cookie for it.

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? "";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
        <Toaster position="top-right" richColors closeButton />
      </QueryClientProvider>
    </GoogleOAuthProvider>
  </StrictMode>,
);
