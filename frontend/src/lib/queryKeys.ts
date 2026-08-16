// =============================================================================
// lib/queryKeys.ts — Typed query key factory
//
// Centralised key factory ensures:
//  • No typos across components
//  • Easy cache invalidation (e.g. invalidateQueries({ queryKey: queryKeys.jobs.all() }))
//  • Hierarchical keys enable coarse-grained invalidation
// =============================================================================

export const queryKeys = {
  // ── Company ────────────────────────────────────────────────────────────────
  company: {
    all: () => ["company"] as const,
    detail: () => ["company", "detail"] as const,
  },

  // ── Jobs ───────────────────────────────────────────────────────────────────
  jobs: {
    all: () => ["jobs"] as const,
    list: (params?: { page?: number; status?: string }) =>
      ["jobs", "list", params] as const,
    detail: (jobId: string) => ["jobs", "detail", jobId] as const,
  },

  // ── Candidates ─────────────────────────────────────────────────────────────
  candidates: {
    all: () => ["candidates"] as const,
    list: (params?: {
      jobOpeningId?: string;
      page?: number;
      status?: string;
      search?: string;
    }) => ["candidates", "list", params] as const,
    detail: (candidateId: string) =>
      ["candidates", "detail", candidateId] as const,
  },

  // ── Sessions ───────────────────────────────────────────────────────────────
  sessions: {
    all: () => ["sessions"] as const,
    list: (params?: { page?: number; status?: string; candidateId?: string }) =>
      ["sessions", "list", params] as const,
    detail: (sessionId: string) => ["sessions", "detail", sessionId] as const,
    evaluation: (sessionId: string) =>
      ["sessions", "evaluation", sessionId] as const,
  },

  // ── Billing ────────────────────────────────────────────────────────────────
  billing: {
    all: () => ["billing"] as const,
    wallet: () => ["billing", "wallet"] as const,
    transactions: (params?: { page?: number }) =>
      ["billing", "transactions", params] as const,
  },

  // ── Team ──────────────────────────────────────────────────────────────────
  team: {
    all: () => ["team"] as const,
    list: () => ["team", "list"] as const,
  },

  // ── Candidate interview room ───────────────────────────────────────────────
  candidate: {
    all: () => ["candidate"] as const,
    session: (sessionId: string) =>
      ["candidate", "session", sessionId] as const,
    questions: (sessionId: string) =>
      ["candidate", "questions", sessionId] as const,
  },
} as const;
