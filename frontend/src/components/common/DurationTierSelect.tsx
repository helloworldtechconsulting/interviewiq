// =============================================================================
// DurationTierSelect.tsx — pick the interview length for a job
//
// PRD v2.1 §7.2.1. Four tiers, and the crucial thing this control has to
// communicate is that ALL FOUR COST THE SAME ₹100:
//
//   "The marginal cost of a longer interview is LLM tokens measured in paise and
//    object storage measured in fractions of a rupee — nothing that justifies a
//    pricing tier, and per-minute pricing would push employers toward the wrong
//    tier for the role. The tier is a product-fit decision, not a monetisation
//    lever."
//
// If the price were left implicit, a recruiter would reasonably assume the
// 60-minute option costs more and pick a shorter one to save money — choosing
// badly for the role, which is exactly what flat pricing exists to prevent.
// =============================================================================

import { Check } from "lucide-react";

import { DURATION_TIERS, type DurationTier } from "@/types";
import { cn } from "@/lib/utils";

interface DurationTierSelectProps {
  value: DurationTier;
  onChange: (tier: DurationTier) => void;
  disabled?: boolean;
}

const ORDER: DurationTier[] = ["QUICK", "STANDARD", "IN_DEPTH", "COMPREHENSIVE"];

export function DurationTierSelect({ value, onChange, disabled }: DurationTierSelectProps) {
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-2">
        {ORDER.map((tier) => {
          const spec = DURATION_TIERS[tier];
          const selected = value === tier;

          return (
            <button
              key={tier}
              type="button"
              disabled={disabled}
              onClick={() => onChange(tier)}
              className={cn(
                "rounded-md border p-3 text-left transition-colors disabled:opacity-50",
                selected ? "border-primary bg-primary/5" : "hover:bg-muted",
              )}
            >
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">{spec.label}</span>
                {selected && <Check className="h-4 w-4 text-primary" />}
              </div>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {spec.minutes} minutes · {spec.questions} questions
              </p>
              <p className="mt-1 text-xs text-muted-foreground">{spec.description}</p>
            </button>
          );
        })}
      </div>

      {/* Said once, plainly, under the whole control. */}
      <p className="text-xs text-muted-foreground">
        Every length costs the same <strong>₹100</strong> per completed interview. Pick the
        one that fits the role, not the budget.
      </p>
    </div>
  );
}
