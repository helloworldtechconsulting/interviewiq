// =============================================================================
// lib/utils.ts — Shared utility functions
// =============================================================================

import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import { format, formatDistanceToNow, parseISO } from "date-fns";

// ── Tailwind class merger (shadcn/ui convention) ──────────────────────────────

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

// ── Currency formatting (Indian Rupees, amounts in paise) ─────────────────────

export function formatRupees(paise: number): string {
  const rupees = paise / 100;
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(rupees);
}

export function paiseToRupees(paise: number): number {
  return paise / 100;
}

export function rupeesToPaise(rupees: number): number {
  return Math.round(rupees * 100);
}

// ── Date formatting ────────────────────────────────────────────────────────────

export function formatDate(isoString: string): string {
  try {
    return format(parseISO(isoString), "dd MMM yyyy");
  } catch {
    return isoString;
  }
}

export function formatDateTime(isoString: string): string {
  try {
    return format(parseISO(isoString), "dd MMM yyyy, hh:mm a");
  } catch {
    return isoString;
  }
}

export function formatRelative(isoString: string): string {
  try {
    return formatDistanceToNow(parseISO(isoString), { addSuffix: true });
  } catch {
    return isoString;
  }
}

// ── String helpers ─────────────────────────────────────────────────────────────

export function initials(name: string): string {
  return name
    .split(" ")
    .map((w) => w[0]?.toUpperCase() ?? "")
    .slice(0, 2)
    .join("");
}

export function truncate(str: string, maxLength: number): string {
  if (str.length <= maxLength) return str;
  return str.slice(0, maxLength - 1) + "…";
}

// ── Status label / colour mappings ────────────────────────────────────────────

export const jobStatusLabel: Record<string, string> = {
  ACTIVE: "Active",
  CLOSED: "Closed",
  ARCHIVED: "Archived",
};

export const jobStatusColour: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-700",
  CLOSED: "bg-red-100 text-red-700",
  ARCHIVED: "bg-yellow-100 text-yellow-700",
};

export const pipelineStatusLabel: Record<string, string> = {
  PENDING: "Pending",
  IN_PROGRESS: "Processing",
  DONE: "Done",
  FAILED: "Failed",
};

export const pipelineStatusColour: Record<string, string> = {
  PENDING: "bg-gray-100 text-gray-600",
  IN_PROGRESS: "bg-blue-100 text-blue-700",
  DONE: "bg-green-100 text-green-700",
  FAILED: "bg-red-100 text-red-700",
};

export const candidateStatusLabel: Record<string, string> = {
  APPLIED: "Applied",
  SCREENING: "Screening",
  INTERVIEW_SCHEDULED: "Interview Scheduled",
  INTERVIEW_DONE: "Interview Done",
  OFFER_EXTENDED: "Offer Extended",
  HIRED: "Hired",
  REJECTED: "Rejected",
  WITHDRAWN: "Withdrawn",
};

export const candidateStatusColour: Record<string, string> = {
  APPLIED: "bg-blue-100 text-blue-700",
  SCREENING: "bg-purple-100 text-purple-700",
  INTERVIEW_SCHEDULED: "bg-indigo-100 text-indigo-700",
  INTERVIEW_DONE: "bg-cyan-100 text-cyan-700",
  OFFER_EXTENDED: "bg-orange-100 text-orange-700",
  HIRED: "bg-green-100 text-green-700",
  REJECTED: "bg-red-100 text-red-700",
  WITHDRAWN: "bg-gray-100 text-gray-700",
};

export const sessionStatusLabel: Record<string, string> = {
  INVITED: "Invited",
  STARTED: "In Progress",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
  ERROR: "Error",
};

export const sessionStatusColour: Record<string, string> = {
  INVITED: "bg-blue-100 text-blue-700",
  STARTED: "bg-yellow-100 text-yellow-700",
  COMPLETED: "bg-green-100 text-green-700",
  CANCELLED: "bg-gray-100 text-gray-700",
  EXPIRED: "bg-orange-100 text-orange-700",
  ERROR: "bg-red-100 text-red-700",
};

// ── Presigned S3 upload helper ─────────────────────────────────────────────────

/**
 * Upload a file to S3 via a presigned PUT URL.
 * Uses XHR (not fetch) so we can track upload progress.
 *
 * @param presignedUrl  URL returned from backend
 * @param file          File to upload
 * @param onProgress    Called with 0–100 progress
 */
export function uploadToS3(
  presignedUrl: string,
  file: File,
  onProgress?: (pct: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", presignedUrl);
    xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");

    if (onProgress) {
      xhr.upload.addEventListener("progress", (e) => {
        if (e.lengthComputable) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      });
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(`S3 upload failed: ${xhr.status}`));
      }
    };

    xhr.onerror = () => reject(new Error("S3 upload network error"));
    xhr.send(file);
  });
}

// ── Razorpay Checkout.js loader ────────────────────────────────────────────────

const RAZORPAY_SCRIPT_URL = "https://checkout.razorpay.com/v1/checkout.js";
let razorpayLoaded = false;

export function loadRazorpay(): Promise<void> {
  if (razorpayLoaded) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = RAZORPAY_SCRIPT_URL;
    script.onload = () => {
      razorpayLoaded = true;
      resolve();
    };
    script.onerror = () => reject(new Error("Failed to load Razorpay"));
    document.body.appendChild(script);
  });
}
