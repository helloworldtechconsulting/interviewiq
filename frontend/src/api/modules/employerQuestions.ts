// =============================================================================
// api/modules/employerQuestions.ts — the employer question bank (PRD v2.1 §7.5.8)
// =============================================================================

import { apiClient } from "@/api/client";
import type { QuestionSafetyStatus } from "@/types";

const base = (jobId: string) => `/api/v1/jobs/${jobId}/questions`;

export interface EmployerQuestion {
  id: string;
  questionText: string;
  safetyStatus: QuestionSafetyStatus;
  /**
   * The prohibited category, named so the employer can correct the question.
   * §7.5.8 requires the refusal to name it — a refusal they cannot act on is
   * worse than none.
   */
  rejectionReason: string | null;
  displayOrder: number;
  /** Whether this question will actually be asked. */
  usable: boolean;
  createdAt: string;
}

export interface UploadResult {
  totalCount: number;
  /** Cleared the safety filter. */
  acceptedCount: number;
  /** Refused; each carries its category. */
  refusedCount: number;
  questions: EmployerQuestion[];
}

export const employerQuestionsApi = {
  list: (jobId: string) =>
    apiClient.get<EmployerQuestion[]>(base(jobId)).then((r) => r.data),

  /**
   * Uploads questions from a CSV (parsed client-side) or a pasted textarea.
   * Returns a result per question, because a partially-refused upload is the
   * normal case rather than an error.
   */
  upload: (jobId: string, payload: { questions?: string[]; pastedText?: string }) =>
    apiClient.post<UploadResult>(base(jobId), payload).then((r) => r.data),

  delete: (jobId: string, questionId: string) =>
    apiClient.delete(`${base(jobId)}/${questionId}`).then(() => undefined),

  /**
   * Reorders the bank. Order is load-bearing: when an employer supplies more
   * questions than the tier holds, the extras rotate across candidates in this
   * order, so it decides which questions every candidate is guaranteed.
   */
  reorder: (jobId: string, questionIds: string[]) =>
    apiClient.put(`${base(jobId)}/order`, { questionIds }).then(() => undefined),
};
