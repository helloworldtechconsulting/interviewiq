// =============================================================================
// types/index.ts — All domain types mirroring the InterviewEngine backend API
// =============================================================================

// ── Shared / Pagination ───────────────────────────────────────────────────────

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;       // 0-based current page
  size: number;
  first: boolean;
  last: boolean;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp: string;
  fieldErrors?: Record<string, string>;
}

// ── Enums ─────────────────────────────────────────────────────────────────────

/**
 * PLATFORM_STAFF is InterviewEngine's own staff, not a customer role (V053). It is
 * the only value that unlocks the platform console, and no customer-facing
 * screen should ever offer it as a choice.
 */
export type UserRole = "ADMIN" | "RECRUITER" | "VIEWER" | "PLATFORM_STAFF";

export type CompanyStatus = "ACTIVE" | "INACTIVE" | "SUSPENDED";

export type CompanySize = "STARTUP" | "SMALL" | "MEDIUM" | "LARGE";

export type JobStatus = "ACTIVE" | "ARCHIVED" | "CLOSED";

export type LocationType = "REMOTE" | "ONSITE" | "HYBRID";

export type EmploymentType = "FULL_TIME" | "PART_TIME" | "CONTRACT" | "INTERNSHIP";

export type PipelineStatus = "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED";

/**
 * Interview session lifecycle (PRD v2.1 §7.4.4).
 *
 * SCHEDULED and EVALUATING are new in v2.1, and STARTED is renamed IN_PROGRESS.
 *
 * EVALUATING is user-visible on purpose: the PRD is explicit that it must not be
 * hidden behind a spinner, because recruiters running hiring drives need to see
 * which reports are still pending.
 */
export type SessionStatus =
  | "INVITED"       // link sent, not yet scheduled
  | "SCHEDULED"     // candidate picked a time; capacity buckets held
  | "IN_PROGRESS"   // candidate is in the interview room
  | "EVALUATING"    // interview finished, scoring running
  | "COMPLETED"     // report ready
  | "CANCELLED"
  | "EXPIRED"
  /** Booked a slot and did not attend (V054). Distinct from EXPIRED, which
      means an invite was never taken up at all — and not charged. */
  | "NO_SHOW"
  | "ERROR";

/**
 * Per-job interview length (PRD v2.1 §7.2.1). All four cost Rs.100 flat — the
 * tier is a product-fit decision, not a monetisation lever.
 */
export type DurationTier = "QUICK" | "STANDARD" | "IN_DEPTH" | "COMPREHENSIVE";

export const DURATION_TIERS: Record<
  DurationTier,
  { label: string; minutes: number; questions: number; description: string }
> = {
  QUICK: {
    label: "Quick screen",
    minutes: 20,
    questions: 8,
    description: "High-volume funnels, fresher roles, first-pass filtering",
  },
  STANDARD: {
    label: "Standard",
    minutes: 35,
    questions: 15,
    description: "The general-purpose first-round screen",
  },
  IN_DEPTH: {
    label: "In-depth",
    minutes: 45,
    questions: 20,
    description: "Mid-senior individual contributors, specialist roles",
  },
  COMPREHENSIVE: {
    label: "Comprehensive",
    minutes: 60,
    questions: 26,
    description: "Senior and lead roles where the screen replaces a technical call",
  },
};

/** Where a question came from (PRD v2.1 §7.5.8). Shown on the report. */
export type QuestionSource = "AI" | "EMPLOYER";

/** Safety-filter outcome on an employer-supplied question (PRD v2.1 §7.5.8). */
export type QuestionSafetyStatus = "PENDING" | "APPROVED" | "REJECTED";

export type TransactionType =
  | "TOPUP"          // paid, invoiced, GST-bearing
  | "PROMO_CREDIT"   // free credit — never invoiced (PRD v2.1 §7.8.3)
  | "PROMO_EXPIRY"   // reversing entry when unspent promo credit lapses
  | "RESERVATION"
  | "SETTLEMENT"
  | "RELEASE"
  | "REFUND";

export type TransactionStatus = "PENDING" | "CONFIRMED" | "RELEASED" | "FAILED";

export type QuestionDifficulty = "EASY" | "MEDIUM" | "HARD";

export type SessionEventType =
  | "SESSION_STARTED"
  | "SESSION_ENDED"
  | "TAB_SWITCH"
  | "CAMERA_OFF"
  | "CAMERA_ON"
  | "MULTI_FACE_DETECTED"
  | "AUDIO_MUTED"
  | "AUDIO_UNMUTED"
  | "SCREEN_SHARE_STARTED"
  | "SCREEN_SHARE_STOPPED"
  | "CONNECTION_LOST"
  | "CONNECTION_RESTORED";

export type SuppressionReason = "BOUNCE" | "COMPLAINT" | "MANUAL";

// ── Auth ──────────────────────────────────────────────────────────────────────

export interface CompanyOnboardRequest {
  companyName: string;
  slug?: string;        // optional — auto-generated from companyName when omitted
  domain?: string;      // optional corporate email domain
  adminName: string;    // ← backend field is adminName, NOT fullName
  email: string;
  password: string;
}

export interface OnboardResponse {
  slug: string;   // company slug — needed for all subsequent /api/v1/{slug}/auth/* calls
  email: string;
}

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface UserResponse {
  id: string;
  companyId: string;
  fullName: string;
  email: string;
  role: UserRole;
  active: boolean;
  emailVerified: boolean;
  lastLoginAt: string | null;  // null for accounts that have never completed a login
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  user: UserResponse;
}

export interface RefreshRequest {
}

export interface VerifyOtpRequest {
  email: string;
  otp: string;
}

export interface ResendVerificationRequest {
  email: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

// JWT payload decoded client-side (no library needed).
// Claim names are set by TokenService.java — "cid" is the compact form of companyId.
export interface JwtPayload {
  sub: string;      // userId
  cid: string;      // companyId (backend uses short claim name "cid")
  email: string;
  role: UserRole;
  exp: number;
  iat: number;
}

// ── Company ───────────────────────────────────────────────────────────────────

export interface Company {
  id: string;
  name: string;
  slug: string;
  domain: string | null;
  status: CompanyStatus;
  website: string | null;
  industry: string | null;
  logoS3Key: string | null;
  size: CompanySize | null;
  gstNumber: string | null;
  createdAt: string;
}

export interface UpdateCompanyRequest {
  name?: string;
  domain?: string;      // empty string clears the field
  website?: string;     // empty string clears the field
  industry?: string;
  size?: CompanySize;
  gstNumber?: string;   // empty string clears the field
}

export interface LogoUploadUrlResponse {
  uploadUrl: string;
  objectKey: string;
}

// ── User / Team ───────────────────────────────────────────────────────────────

export interface TeamMember {
  id: string;
  companyId: string;
  fullName: string;
  email: string;
  role: UserRole;
  active: boolean;
  emailVerified: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface InviteTeamMemberRequest {
  fullName: string;
  email: string;
  role: UserRole;
}

export interface UpdateMemberRequest {
  role?: UserRole;
  active?: boolean;
}

// ── Job ───────────────────────────────────────────────────────────────────────

export interface Job {
  id: string;
  companyId: string;
  createdBy: string;
  title: string;
  department: string | null;
  locationType: LocationType | null;
  employmentType: EmploymentType | null;
  jdS3Key: string | null;
  jdExtractionStatus: PipelineStatus;
  description: string | null;
  experienceMin: number | null;
  experienceMax: number | null;
  /** Interview length for this opening (PRD v2.1 §7.2.1). Defaults to STANDARD. */
  durationTier: DurationTier;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateJobRequest {
  title: string;
  durationTier?: DurationTier;
  department?: string;
  locationType?: LocationType;
  employmentType?: EmploymentType;
  description?: string;
  experienceMin?: number;
  experienceMax?: number;
}

export interface UpdateJobRequest {
  title?: string;
  durationTier?: DurationTier;
  department?: string;
  locationType?: LocationType;
  employmentType?: EmploymentType;
  status?: JobStatus;
  description?: string;
  experienceMin?: number;
  experienceMax?: number;
}

export interface JdUploadUrlResponse {
  uploadUrl: string;
  objectKey: string;
}

// ── Candidate ─────────────────────────────────────────────────────────────────

export interface Candidate {
  id: string;
  companyId: string;
  jobOpeningId: string;
  email: string;
  fullName: string;
  phone: string | null;
  resumeS3Key: string | null;
  resumeExtractionStatus: PipelineStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCandidateRequest {
  jobOpeningId: string;
  email: string;
  fullName: string;
  phone?: string;
}

export interface UpdateCandidateRequest {
  fullName?: string;
  phone?: string;
}

export interface ResumeUploadUrlResponse {
  uploadUrl: string;
  objectKey: string;
}

// ── Session ───────────────────────────────────────────────────────────────────

export interface Session {
  id: string;
  companyId: string;
  jobOpeningId: string;
  candidateId: string;
  status: SessionStatus;
  questionGenerationStatus: PipelineStatus;
  scheduledAt: string | null;
  inviteExpiresAt: string;
  startedAt: string | null;
  endedAt: string | null;
  durationSeconds: number | null;
  recordingS3Key: string | null;
  proctoringFlagsJsonb: string | null;
  cancelledAt: string | null;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface InterviewInitData {
  sessionId: string;
  status: SessionStatus;
  questionGenerationStatus: PipelineStatus;
  questionsJson: string | null;
  recordingUploadUrl: string;
  recordingS3Key: string;
  scheduledAt: string | null;
  inviteExpiresAt: string;
  googleVerified: boolean;
}

export interface InterviewQuestion {
  order: number;
  text: string;
  dimension: string;
  difficulty?: string;
  answer?: string;
}

export interface CreateSessionRequest {
  jobOpeningId: string;
  candidateId: string;
  scheduledAt: string;   // ISO-8601 UTC timestamp — must be a future time
}

export interface ProctoringFlag {
  type: SessionEventType;
  count?: number;
  totalSeconds?: number;
  firstOccurrence?: string;
}

export interface SessionEvent {
  id: string;
  companyId: string;
  sessionId: string;
  eventType: SessionEventType;
  metadata: Record<string, unknown> | null;
  createdAt: string;
}

// ── Evaluation ────────────────────────────────────────────────────────────────

export interface EvaluationScore {
  technicalSkills: number;
  communication: number;
  problemSolving: number;
  culturalFit: number;
  overallScore: number;
}

export interface EvaluationQuestion {
  id: string;
  text: string;
  difficulty: QuestionDifficulty;
  topic: string;
  answer?: string;
  score?: number;
  feedback?: string;
}

/** One dimension's narrative, with the answers it cites (PRD v2.1 §7.6). */
export interface DimensionEvidence {
  narrative: string;
  /** Answer indexes supporting the claim. A claim with none is a defect. */
  citedAnswerIndexes: number[];
}

export interface PerQuestionEvidence {
  questionIndex: number;
  score?: number;
  narrative: string;
}

/**
 * Per-question narrative evidence (PRD v2.1 §7.6).
 *
 * Validated server-side before the report is persisted — "a report whose
 * narrative does not cite answers is a defect, not a stylistic preference".
 */
export interface Evidence {
  overallSummary: string;
  dimensions: Record<string, DimensionEvidence>;
  perQuestion: PerQuestionEvidence[];
}

/** One answer as stored, for rendering the transcript alongside the evidence. */
export interface SessionAnswer {
  questionIndex: number;
  questionText: string;
  /** EMPLOYER-sourced questions are labelled on the report (§7.5.8). */
  questionSource: QuestionSource;
  transcriptText: string | null;
  score: number | null;
  skipped: boolean;
  isFollowUp: boolean;
}

export interface Evaluation {
  id: string;
  sessionId: string;
  scores: EvaluationScore;
  summary: string;
  strengths: string[];
  improvements: string[];
  recommendation: "HIRE" | "HOLD" | "REJECT";
  questions: EvaluationQuestion[];
  /** The v2.1 evidence payload. Absent on reports generated before v2.1. */
  evidence?: Evidence;
  answers?: SessionAnswer[];
  /** True when the candidate answered some but not all questions (§7.5.7). */
  partial?: boolean;
  transcript?: string;
  /**
   * The recruiter's private notes (INTIQ-29). Never sent to a model — an
   * opinion formed after the interview must not influence the evaluation of it.
   */
  employerNotes?: string | null;
  createdAt: string;
}

// ── Billing / Wallet ──────────────────────────────────────────────────────────

/**
 * Wallet balance, split into paid and promotional (PRD v2.1 §7.7, §7.8.3).
 *
 * The split is surfaced rather than summed because "a customer must never be
 * surprised about which money is being spent". Promotional credit is always
 * spent first, so a company on a free trial watches promoBalancePaise fall while
 * paidBalancePaise stays untouched — which is only reassuring if they can see
 * both numbers.
 */
export interface Wallet {
  id: string;
  companyId: string;
  /** Money the company bought. GST invoices are drawn against this alone. */
  paidBalancePaise: number;
  /** Free credit — spent first, never invoiced. */
  promoBalancePaise: number;
  /** paid + promotional. What the low-balance threshold is measured against. */
  totalBalancePaise: number;
  /** Ring-fenced for invited sessions and in-flight imports. */
  reservedPaise: number;
  /** total − reserved. What a new session can draw on. */
  availablePaise: number;
  /** Earliest expiry among outstanding grants, or null if none lapse. */
  promoExpiresAt: string | null;
  /** Whether the persistent low-balance banner should show (Rs.300 or below). */
  lowBalance: boolean;
}

export interface WalletTransaction {
  id: string;
  companyId: string;
  walletId: string;
  sessionId: string | null;
  transactionType: TransactionType;
  amountPaise: number;
  balanceAfterPaise: number;
  status: TransactionStatus;
  razorpayOrderId: string | null;
  razorpayPaymentId: string | null;
  description: string | null;
  createdAt: string;
}

export interface CreateOrderRequest {
  amountPaise: number;
}

export interface RazorpayOrder {
  razorpayOrderId: string;   // Razorpay order ID (matches TopUpResponse.razorpayOrderId)
  amountPaise: number;
  currency: string;
  keyId: string;             // Razorpay key ID for Checkout.js
}

export interface EmailSuppression {
  id: string;
  email: string;
  reason: SuppressionReason;
  providerNotificationId: string | null;
  notes: string | null;
  createdAt: string;
}

// ── Candidate Interview Room (invite-token auth) ──────────────────────────────

export interface CandidateSession {
  sessionId: string;
  candidateName: string;
  jobTitle: string;
  companyName: string;
  scheduledAt: string | null;
  status: SessionStatus;
}

export interface CandidateQuestion {
  id: string;
  text: string;
  difficulty: QuestionDifficulty;
  topic: string;
}

export interface SubmitAnswerRequest {
  questionId: string;
  answer: string;
}

// ── Razorpay Checkout.js (browser global) ─────────────────────────────────────

export interface RazorpayOptions {
  key: string;
  amount: number;
  currency: string;
  name: string;
  description?: string;
  order_id: string;
  handler: (response: RazorpayPaymentResponse) => void;
  prefill?: {
    name?: string;
    email?: string;
    contact?: string;
  };
  theme?: {
    color?: string;
  };
  modal?: {
    ondismiss?: () => void;
  };
}

export interface RazorpayPaymentResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

// Augment the global Window to include Razorpay
declare global {
  interface Window {
    Razorpay: new (options: RazorpayOptions) => {
      open: () => void;
    };
  }
}

/**
 * One thing the browser noticed during an interview (INTIQ-29).
 *
 * Deliberately carries no severity or verdict. These signals are weak
 * individually — a tab switch might be someone checking the time — and
 * summarising them into a judgement about a person, with their job at stake,
 * is not something the system has a basis for.
 */
export interface ProctoringEvent {
  id: string;
  sessionId: string;
  eventType: "TAB_SWITCH" | "CAMERA_OFF";
  metadata?: string | null;
  occurredAt: string;
}
