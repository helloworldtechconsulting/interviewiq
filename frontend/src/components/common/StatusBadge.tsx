import { cn } from "@/lib/utils";
import {
  candidateStatusColour,
  candidateStatusLabel,
  jobStatusColour,
  jobStatusLabel,
  pipelineStatusColour,
  pipelineStatusLabel,
  sessionStatusColour,
  sessionStatusLabel,
} from "@/lib/utils";

type StatusKind = "job" | "candidate" | "session" | "pipeline";

interface StatusBadgeProps {
  kind: StatusKind;
  status: string;
  className?: string;
}

const labelMap: Record<StatusKind, Record<string, string>> = {
  job: jobStatusLabel,
  candidate: candidateStatusLabel,
  session: sessionStatusLabel,
  pipeline: pipelineStatusLabel,
};

const colourMap: Record<StatusKind, Record<string, string>> = {
  job: jobStatusColour,
  candidate: candidateStatusColour,
  session: sessionStatusColour,
  pipeline: pipelineStatusColour,
};

export function StatusBadge({ kind, status, className }: StatusBadgeProps) {
  const label = labelMap[kind][status] ?? status;
  const colour = colourMap[kind][status] ?? "bg-gray-100 text-gray-700";

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        colour,
        className,
      )}
    >
      {label}
    </span>
  );
}
