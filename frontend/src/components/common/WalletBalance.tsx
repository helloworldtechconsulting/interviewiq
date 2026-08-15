// =============================================================================
// WalletBalance.tsx — the paid/promotional split
//
// PRD v2.1 §7.7 and §7.8.3. The dashboard shows "Balance ₹700 (₹200 promotional,
// expires 30 Sep)", and the reason is stated plainly in the spec:
//
//   "A customer must never be surprised about which money is being spent."
//
// Promotional credit is always spent FIRST, so a company on a free trial watches
// their promotional balance fall while the paid balance sits untouched. That is
// only reassuring if they can see both numbers — showing one combined figure
// would make free credit look like it was their own money draining away.
// =============================================================================

import { Gift, Wallet as WalletIcon } from "lucide-react";

import type { Wallet } from "@/types";
import { cn, formatRupees } from "@/lib/utils";

interface WalletBalanceProps {
  wallet: Wallet;
  /** Rs.100 per completed interview, at every duration tier (§7.8.1). */
  sessionCostPaise?: number;
  compact?: boolean;
  className?: string;
}

export function WalletBalance({
  wallet,
  sessionCostPaise = 10_000,
  compact = false,
  className,
}: WalletBalanceProps) {
  const hasPromo = wallet.promoBalancePaise > 0;
  const interviewsRemaining = Math.floor(wallet.availablePaise / sessionCostPaise);

  const promoExpiry = wallet.promoExpiresAt
    ? new Date(wallet.promoExpiresAt).toLocaleDateString(undefined, {
        day: "numeric",
        month: "short",
      })
    : null;

  if (compact) {
    return (
      <span className={cn("inline-flex items-center gap-1.5 text-sm", className)}>
        <WalletIcon className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="font-medium">{formatRupees(wallet.totalBalancePaise)}</span>
        {hasPromo && (
          <span className="text-xs text-muted-foreground">
            ({formatRupees(wallet.promoBalancePaise)} free)
          </span>
        )}
      </span>
    );
  }

  return (
    <div className={cn("space-y-2", className)}>
      <div>
        <p className="text-2xl font-bold">{formatRupees(wallet.totalBalancePaise)}</p>
        <p className="text-xs text-muted-foreground">
          about {interviewsRemaining} interview{interviewsRemaining === 1 ? "" : "s"} remaining
        </p>
      </div>

      {hasPromo && (
        <div className="flex items-start gap-2 rounded-md bg-emerald-50 p-2 text-xs text-emerald-900">
          <Gift className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <div>
            <p>
              <strong>{formatRupees(wallet.promoBalancePaise)}</strong> of this is free
              credit{promoExpiry ? `, expiring ${promoExpiry}` : ""}.
            </p>
            {/* Spend order is stated, not implied. It is the reassurance that
                stops a customer opening a refund request when their balance
                moves (§7.8.3). */}
            <p className="mt-0.5 opacity-80">We use your free credit first.</p>
          </div>
        </div>
      )}

      {wallet.reservedPaise > 0 && (
        <p className="text-xs text-muted-foreground">
          {formatRupees(wallet.reservedPaise)} is held for interviews already sent out.
        </p>
      )}

      {/* The persistent low-balance banner (§7.7), at Rs.300 or below, counting
          paid and promotional together. */}
      {wallet.lowBalance && (
        <div className="rounded-md bg-amber-50 p-2 text-xs text-amber-900">
          Your balance is running low. Top up to keep sending interview invitations.
        </div>
      )}
    </div>
  );
}
