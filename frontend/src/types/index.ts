export interface User {
  id: string;
  name: string;
  email: string;
  companyName: string;
  createdAt: string;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

export interface AuthState {
  user: User | null;
  tokens: AuthTokens | null;
  isLoading: boolean;
  error: string | null;
}

export type JobStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED';
export type CandidateStatus = 'INVITED' | 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'REJECTED';
export type InterviewStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';

export interface JobOpening {
  id: string;
  companyId: string;
  title: string;
  department: string;
  locationType: 'REMOTE' | 'HYBRID' | 'ONSITE';
  employmentType: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT';
  description: string;
  status: JobStatus;
  jdFileUrl?: string;
  interviewCount: number;
  averageScore: number;
  createdAt: string;
  updatedAt: string;
}

export interface Candidate {
  id: string;
  jobOpeningId: string;
  name: string;
  email: string;
  phone: string;
  resumeUrl?: string;
  status: CandidateStatus;
  score?: number;
  createdAt: string;
  updatedAt: string;
}

export interface InterviewSession {
  id: string;
  candidateId: string;
  jobOpeningId: string;
  status: InterviewStatus;
  startTime?: string;
  endTime?: string;
  duration?: number;
  overallScore?: number;
  transcript?: string;
  videoUrl?: string;
  videoExpiresAt?: string;
  antiCheatFlags?: AntiCheatFlag[];
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AntiCheatFlag {
  id: string;
  sessionId: string;
  type: 'TAB_SWITCH' | 'CAMERA_OFF' | 'FACE_NOT_DETECTED' | 'MULTIPLE_FACES';
  timestamp: string;
  severity: 'WARNING' | 'CRITICAL';
}

export interface EvaluationReport {
  sessionId: string;
  overallScore: number;
  dimensionScores: DimensionScore[];
  recommendation: 'STRONG_YES' | 'YES' | 'MAYBE' | 'NO' | 'STRONG_NO';
  transcript: TranscriptItem[];
  createdAt: string;
}

export interface DimensionScore {
  dimension: string;
  score: number;
  feedback: string;
}

export interface TranscriptItem {
  questionNumber: number;
  question: string;
  dimension: string;
  answer: string;
  answerScore: number;
  feedback: string;
}

export interface AvailabilitySlot {
  id: string;
  jobOpeningId: string;
  startTime: string;
  endTime: string;
  maxInterviews: number;
  bookedInterviews: number;
  createdAt: string;
}

export interface BillingTransaction {
  id: string;
  companyId: string;
  type: 'TOPUP' | 'DEDUCTION';
  amountPaise: number;
  balancePaise: number;
  description: string;
  razorpayOrderId?: string;
  razorpayPaymentId?: string;
  status: 'PENDING' | 'SUCCESS' | 'FAILED';
  createdAt: string;
}

export interface BillingInfo {
  balancePaise: number;
  totalDeductionsPaise: number;
  totalTopupsPaise: number;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

export interface CandidateVerifyResponse {
  sessionId: string;
  jobOpeningId: string;
  candidateName: string;
  companyName: string;
  jobTitle: string;
  instructions: string;
  totalQuestions: number;
  maxDurationMinutes: number;
  accessToken: string;
}

export interface InterviewQuestion {
  type: 'QUESTION';
  questionNumber: number;
  totalQuestions: number;
  text: string;
  dimension: string;
}

export interface InterviewAnswer {
  type: 'ANSWER';
  questionNumber: number;
  text: string;
}

export interface InterviewComplete {
  type: 'INTERVIEW_COMPLETE';
  message: string;
}

export type InterviewWebSocketMessage = InterviewQuestion | InterviewAnswer | InterviewComplete | { type: 'ERROR'; message: string };
