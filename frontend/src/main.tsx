import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";

import "./index.css";
import { router } from "@/router";
import { queryClient } from "@/lib/queryClient";
import { authStore } from "@/stores/authStore";
import { AuthProvider } from "@/components/common/AuthProvider";

// Synchronously read the refresh token from localStorage so AuthProvider
// can immediately attempt the token exchange before the first render.
authStore.getState().hydrateFromStorage();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
      <Toaster position="top-right" richColors closeButton />
    </QueryClientProvider>
  </StrictMode>,
);
