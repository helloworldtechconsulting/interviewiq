// =============================================================================
// types/index.ts — All domain types mirroring the InterviewIQ backend API
//
// IMPORTANT: All types here match backend JSON responses AFTER the axios
// interceptor has applied snake_case → camelCase transformation.
// E.g. backend "job_opening_id" → frontend "jobOpeningId".
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

export type UserRole = "ADMIN" | "RECRUITER" | "VIEWER";

export type CompanyStatus = "ACTIVE" | "INACTIVE" | "SUSPENDED";

export type CompanySize = "STARTUP" | "SMALL" | "MEDIUM" | "LARGE";

export type JobStatus = "ACTIVE" | "ARCHIVED" | "CLOSED";

export type LocationType = "REMOTE" | "ONSITE" | "HYBRID";

export type EmploymentType = "FULL_TIME" | "PART_TIME" | "CONTRACT" | "INTERNSHIP";

export type PipelineStatus = "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED";

export type SessionStatus =
  | "INVITED"
  | "STARTED"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED"
  | "ERROR";

export type TransactionType =
  | "TOPUP"
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

/**
 * POST /api/v1/companies/register — atomically creates company + admin user + wallet.
 * Matches CompanyOnboardRequest.java fields exactly.
 */
export interface CompanyOnboardRequest {
  companyName: string;
  slug?: string;        // optional — auto-generated from companyName when omitted
  domain?: string;      // optional corporate email domain
  adminName: string;    // ← backend field is adminName, NOT fullName
  email: string;
  password: string;
}

/** Response from POST /api/v1/companies/register */
export interface OnboardResponse {
  slug: string;   // company slug — needed for all subsequent /api/v1/{slug}/auth/* calls
  email: string;
}

/**
 * POST /api/v1/{slug}/auth/register — adds a new user to an EXISTING company.
 * Only used for team-member self-registration, NOT for onboarding a new company.
 */
export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/** Mirrors AuthService/UserResponse.java — backend sends snake_case, interceptor converts to camelCase. */
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
  refreshToken: string;
  user: UserResponse;
}

export interface RefreshRequest {
  refreshToken: string;
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

/** Mirrors CompanyProfileResponse.java */
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

/** Mirrors UpdateCompanyRequest.java — all fields optional (null = do not change) */
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

/** Mirrors JobResponse.java */
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
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors CreateJobRequest.java */
export interface CreateJobRequest {
  title: string;
  department?: string;
  locationType?: LocationType;
  employmentType?: EmploymentType;
  description?: string;
  experienceMin?: number;
  experienceMax?: number;
}

/** Mirrors UpdateJobRequest.java — all fields optional */
export interface UpdateJobRequest {
  title?: string;
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

/** Mirrors CandidateResponse.java */
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

/** Mirrors CreateCandidateRequest.java */
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

/** Mirrors SessionResponse.java */
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
  /** JSON array string — parse client-side when displaying proctoring flags */
  proctoringFlagsJsonb: string | null;
  cancelledAt: string | null;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors InterviewInitResponse.java — returned by
 * GET /api/v1/candidate/interview/init
 */
export interface InterviewInitData {
  sessionId: string;
  status: SessionStatus;
  questionGenerationStatus: PipelineStatus;
  /** JSON array of question objects with optional "answer" field. Null until generation completes. */
  questionsJson: string | null;
  recordingUploadUrl: string;
  recordingS3Key: string;
  scheduledAt: string | null;
  inviteExpiresAt: string;
}

/** One question object parsed from InterviewInitData.questionsJson */
export interface InterviewQuestion {
  order: number;
  text: string;
  dimension: string;
  difficulty?: string;
  answer?: string;
}

/** Mirrors CreateSessionRequest.java */
export interface CreateSessionRequest {
  jobOpeningId: string;
  candidateId: string;
  scheduledAt: string;   // ISO-8601 UTC timestamp — must be a future time
}

export interface SetMeetUrlRequest {
  meetUrl: string;
}

/**
 * One entry in a session's proctoring_flags_jsonb array.
 * Parse session.proctoringFlagsJsonb with JSON.parse() to get this.
 */
export interface ProctoringFlag {
  type: SessionEventType;
  count?: number;
  totalSeconds?: number;
  firstOccurrence?: string;
}

/** Mirrors SessionEvent.java — returned by the proctoring/events endpoint. */
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

export interface Evaluation {
  id: string;
  sessionId: string;
  scores: EvaluationScore;
  summary: string;
  strengths: string[];
  improvements: string[];
  recommendation: "HIRE" | "HOLD" | "REJECT";
  questions: EvaluationQuestion[];
  transcript?: string;
  createdAt: string;
}

// ── Billing / Wallet ──────────────────────────────────────────────────────────

export interface Wallet {
  id: string;
  companyId: string;
  balancePaise: number;      // total balance in paise
  reservedPaise: number;     // funds ring-fenced for active sessions
  availablePaise: number;    // balancePaise − reservedPaise
}

/** Mirrors TransactionResponse.java */
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

// ── Email suppression ─────────────────────────────────────────────────────────

/** Mirrors EmailSuppression.java */
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
  meetUrl: string | null;
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
