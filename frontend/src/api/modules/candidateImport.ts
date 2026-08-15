// =============================================================================
// api/modules/candidateImport.ts — bulk CSV import (PRD v2.1 §7.3.1)
//
// Flow: upload → column mapping → validation preview → confirm.
//
// The two steps are separate because NOTHING is charged until confirm. The
// preview takes no wallet reservation; confirm takes the whole batch atomically
// or refuses it, because "a 50-candidate import that runs out of money at
// candidate 38 is a support ticket and a half-imported opening".
// =============================================================================

import { apiClient } from "@/api/client";

const BASE = "/api/v1/candidates/import";

/** Field name → CSV column index, as CONFIRMED by the recruiter. */
export type ColumnMapping = Record<string, number>;

export interface ParsedRow {
  lineNumber: number;
  name: string;
  email: string;
  phone: string;
  resumeUrl: string;
  problems: string[];
}

export interface RowOutcome {
  lineNumber: number;
  name: string;
  email: string;
  outcome: "VALID" | "DUPLICATE" | "INVALID";
  problems: string[];
}

export interface ImportPreview {
  batchId: string;
  rowCount: number;
  validCount: number;
  duplicateCount: number;
  invalidCount: number;
  /** What the whole batch will reserve, shown before the recruiter commits. */
  reservationRequiredPaise: number;
  /** e.g. "47 valid, 3 duplicates, 2 invalid" — the form §7.3.1 specifies. */
  summary: string;
  rows: RowOutcome[];
}

export interface ImportResult {
  batchId: string;
  importedCount: number;
  reservedPaise: number;
  candidateIds: string[];
}

export const candidateImportApi = {
  /**
   * Reads the header row and returns a PROPOSED mapping for the recruiter to
   * confirm or correct. Fields with no confident match are absent — "we do not
   * guess silently" (§7.3.1).
   */
  proposeMapping: (jobOpeningId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("jobOpeningId", jobOpeningId);
    return apiClient
      .post<{ header: string[]; proposedMapping: ColumnMapping }>(`${BASE}/mapping`, form, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },

  /** Validates and reports counts. Charges nothing. */
  preview: (jobOpeningId: string, file: File, mapping: ColumnMapping) => {
    const form = new FormData();
    form.append("file", file);
    form.append("jobOpeningId", jobOpeningId);
    form.append("mapping", JSON.stringify(mapping));
    return apiClient
      .post<ImportPreview>(`${BASE}/preview`, form, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },

  /** Takes the whole-batch reservation and imports, or refuses entirely. */
  confirm: (batchId: string, file: File, mapping: ColumnMapping) => {
    const form = new FormData();
    form.append("file", file);
    form.append("mapping", JSON.stringify(mapping));
    return apiClient
      .post<ImportResult>(`${BASE}/${batchId}/confirm`, form, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },
};
