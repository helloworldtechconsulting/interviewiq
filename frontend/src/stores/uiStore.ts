// =============================================================================
// stores/uiStore.ts — Global UI state (Zustand)
//
// Keeps lightweight state that needs to be accessible across the component
// tree without prop-drilling: sidebar collapse, global loading overlay, etc.
// =============================================================================

import { create } from "zustand";

interface UiState {
  // Sidebar
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
  toggleSidebar: () => void;

  // Global loading overlay (for blocking operations like payment)
  globalLoading: boolean;
  globalLoadingMessage: string;
  setGlobalLoading: (loading: boolean, message?: string) => void;
}

export const useUiStore = create<UiState>((set) => ({
  // ── Sidebar ────────────────────────────────────────────────────────────────
  sidebarOpen: true,

  setSidebarOpen(open) {
    set({ sidebarOpen: open });
  },

  toggleSidebar() {
    set((s) => ({ sidebarOpen: !s.sidebarOpen }));
  },

  // ── Global loading overlay ─────────────────────────────────────────────────
  globalLoading: false,
  globalLoadingMessage: "Please wait…",

  setGlobalLoading(loading, message = "Please wait…") {
    set({ globalLoading: loading, globalLoadingMessage: message });
  },
}));
