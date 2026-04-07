// =============================================================================
// types/index.ts — All domain types mirroring the InterviewIQ backend API
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

export type JobStatus = "DRAFT" | "OPEN" | "CLOSED" | "ARCHIVED";

export type CandidateStatus =
  | "APPLIED"
  | "SCREENING"
  | "INTERVIEW_SCHEDULED"
  | "INTERVIEW_DONE"
  | "OFFER_EXTENDED"
  | "HIRED"
  | "REJECTED"
  | "WITHDRAWN";

export type SessionStatus =
  | "INVITED"
  | "STARTED"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED"
  | "ERROR";

export type QuestionDifficulty = "EASY" | "MEDIUM" | "HARD";

export type TransactionType =
  | "TOPUP"
  | "RESERVATION"
  | "SETTLEMENT"
  | "RELEASE"
  | "REFUND";

export type PaymentStatus = "CREATED" | "PAID" | "FAILED" | "REFUNDED";

// ── Auth ──────────────────────────────────────────────────────────────────────

export interface RegisterRequest {
  companyName: string;
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

export interface Company {
  id: string;
  name: string;
  website?: string;
  industry?: string;
  logoUrl?: string;
  createdAt: string;
}

export interface UpdateCompanyRequest {
  name?: string;
  website?: string;
  industry?: string;
}

export interface LogoUploadUrlResponse {
  uploadUrl: string;
  key: string;
}

// ── User / Team ───────────────────────────────────────────────────────────────

export interface TeamMember {
  id: string;
  fullName: string;
  email: string;
  role: UserRole;
  active: boolean;
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
  title: string;
  department?: string;
  location?: string;
  description?: string;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateJobRequest {
  title: string;
  department?: string;
  location?: string;
  description?: string;
}

export interface UpdateJobRequest {
  title?: string;
  department?: string;
  location?: string;
  description?: string;
  status?: JobStatus;
}

export interface JdUploadUrlResponse {
  uploadUrl: string;
  key: string;
}

// ── Candidate ─────────────────────────────────────────────────────────────────

export interface Candidate {
  id: string;
  jobId: string;
  jobTitle: string;
  fullName: string;
  email: string;
  phone?: string;
  resumeKey?: string;
  status: CandidateStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCandidateRequest {
  jobId: string;
  fullName: string;
  email: string;
  phone?: string;
}

export interface UpdateCandidateRequest {
  fullName?: string;
  phone?: string;
  status?: CandidateStatus;
}

export interface ResumeUploadUrlResponse {
  uploadUrl: string;
  key: string;
}

// ── Session ───────────────────────────────────────────────────────────────────

export interface Session {
  id: string;
  candidateId: string;
  candidateName: string;
  candidateEmail: string;
  jobId: string;
  jobTitle: string;
  scheduledAt: string;
  durationMinutes: number;
  meetUrl?: string;
  botId?: string;
  status: SessionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSessionRequest {
  candidateId: string;
  scheduledAt: string;       // ISO-8601
  durationMinutes?: number;  // default 60
}

export interface SetMeetUrlRequest {
  meetUrl: string;
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

export interface WalletTransaction {
  id: string;
  companyId: string;
  walletId: string;
  sessionId?: string;
  transactionType: TransactionType;
  amountPaise: number;
  balanceAfterPaise: number;
  status: "CONFIRMED" | "PENDING" | "RELEASED" | "FAILED";
  razorpayOrderId?: string;
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

// ── Candidate Interview Room (invite-token auth) ──────────────────────────────

export interface CandidateSession {
  sessionId: string;
  candidateName: string;
  jobTitle: string;
  companyName: string;
  scheduledAt: string;
  durationMinutes: number;
  meetUrl?: string;
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
