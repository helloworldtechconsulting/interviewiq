// =============================================================================
// lib/queryClient.ts — TanStack Query global client
// =============================================================================

import { QueryClient } from "@tanstack/react-query";
import { AppError } from "@/api/client";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Don't retry on 4xx client errors — retrying won't fix auth/validation issues
      retry: (failureCount, error) => {
        if (error instanceof AppError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
      // Data is considered fresh for 30 seconds by default
      staleTime: 30_000,
      // Keep unused data in cache for 5 minutes
      gcTime: 5 * 60 * 1000,
      // Refetch on window focus (good UX for a dashboard)
      refetchOnWindowFocus: true,
    },
    mutations: {
      retry: false,
    },
  },
});
