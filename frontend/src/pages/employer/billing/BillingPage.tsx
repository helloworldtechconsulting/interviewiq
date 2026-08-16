// =============================================================================
// BillingPage.tsx
//
// • Wallet balance card
// • Top-up flow: enter amount → create Razorpay order → open Checkout.js →
//   confirm payment → poll wallet until balance updates (webhook lag)
// • Transaction history with pagination
// =============================================================================

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import {
  CreditCard,
  TrendingUp,
  ArrowDownLeft,
  ArrowUpRight,
  RotateCcw,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Plus,
} from "lucide-react";

import { billingApi } from "@/api/modules/billing";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import { useAuthUser } from "@/stores/authStore";
import { PageHeader } from "@/components/common/PageHeader";
import { WalletBalance } from "@/components/common/WalletBalance";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  formatDate,
  formatRupees,
  loadRazorpay,
  rupeesToPaise,
} from "@/lib/utils";
import type { RazorpayPaymentResponse, TransactionType, RazorpayOrder } from "@/types";

const PAGE_SIZE = 10;

// ── Top-up schema ─────────────────────────────────────────────────────────────

const topupSchema = z.object({
  amountRupees: z.coerce
    .number()
    .min(100, "Minimum top-up is ₹100")
    .max(100000, "Maximum top-up is ₹1,00,000")
    .int("Amount must be a whole number"),
});
type TopupForm = z.infer<typeof topupSchema>;

// ── Preset amounts ────────────────────────────────────────────────────────────

const PRESETS = [500, 1000, 2000, 5000];

// ── Transaction icon ──────────────────────────────────────────────────────────

function TxIcon({ type }: { type: TransactionType }) {
  if (type === "TOPUP" || type === "REFUND" || type === "RELEASE") {
    return (
      <div className="flex h-8 w-8 items-center justify-center rounded-full bg-green-100">
        <ArrowDownLeft className="h-4 w-4 text-green-600" />
      </div>
    );
  }
  return (
    <div className="flex h-8 w-8 items-center justify-center rounded-full bg-red-100">
      <ArrowUpRight className="h-4 w-4 text-red-600" />
    </div>
  );
}

// ── Top-Up Dialog ─────────────────────────────────────────────────────────────

interface TopupDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function TopupDialog({ open, onOpenChange }: TopupDialogProps) {
  const qc = useQueryClient();
  const user = useAuthUser();
  const [polling, setPolling] = useState(false);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    reset,
    formState: { errors },
  } = useForm<TopupForm>({
    resolver: zodResolver(topupSchema),
    defaultValues: { amountRupees: 1000 },
  });

  const amount = watch("amountRupees");

  function startPolling() {
    // Wallet is credited by the Razorpay webhook — poll for up to 60s
    setPolling(true);
    let attempts = 0;
    const interval = setInterval(() => {
      attempts++;
      void qc.invalidateQueries({ queryKey: queryKeys.billing.wallet() });
      void qc.invalidateQueries({ queryKey: queryKeys.billing.transactions() });
      if (attempts >= 12) {
        clearInterval(interval);
        setPolling(false);
      }
    }, 5000);
  }

  const createOrderMutation = useMutation({
    mutationFn: (amountPaise: number) =>
      billingApi.createOrder({ amountPaise }),
    onSuccess: async (order: RazorpayOrder) => {
      try {
        // Load Razorpay Checkout.js dynamically (not bundled)
        await loadRazorpay();

        const rzp = new window.Razorpay({
          key: order.keyId,
          amount: order.amountPaise,
          currency: order.currency,
          name: "InterviewEngine",
          description: "Wallet Top-up",
          order_id: order.razorpayOrderId,
          prefill: { email: user?.email },
          theme: { color: "hsl(221.2 83.2% 53.3%)" },
          handler: (_response: RazorpayPaymentResponse) => {
            // Payment captured — wallet is credited via Razorpay webhook
            toast.success("Payment successful! Crediting wallet…");
            reset();
            onOpenChange(false);
            startPolling();
          },
          modal: {
            ondismiss: () => {
              toast.info("Payment cancelled.");
            },
          },
        });

        rzp.open();
      } catch {
        toast.error("Could not open payment gateway. Please try again.");
      }
    },
    onError(error) {
      toast.error(
        error instanceof AppError ? error.message : "Could not create order.",
      );
    },
  });

  function onSubmit(data: TopupForm) {
    createOrderMutation.mutate(rupeesToPaise(data.amountRupees));
  }

  const isLoading = createOrderMutation.isPending;

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!isLoading) {
          reset();
          onOpenChange(v);
        }
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Top up wallet</DialogTitle>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          Each AI interview session costs ₹50. Funds are added instantly after
          payment.
        </p>

        <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
          {/* Presets */}
          <div className="space-y-2">
            <Label>Quick select</Label>
            <div className="grid grid-cols-4 gap-2">
              {PRESETS.map((p) => (
                <Button
                  key={p}
                  type="button"
                  variant={amount === p ? "default" : "outline"}
                  size="sm"
                  onClick={() =>
                    setValue("amountRupees", p, { shouldValidate: true })
                  }
                >
                  ₹{p}
                </Button>
              ))}
            </div>
          </div>

          {/* Custom amount */}
          <div className="space-y-1.5">
            <Label htmlFor="amountRupees">Custom amount (₹)</Label>
            <Input
              id="amountRupees"
              type="number"
              min={100}
              max={100000}
              step={50}
              {...register("amountRupees")}
            />
            {errors.amountRupees && (
              <p className="text-xs text-destructive">
                {errors.amountRupees.message}
              </p>
            )}
            <p className="text-xs text-muted-foreground">
              ≈{" "}
              {Math.floor((amount ?? 0) / 50)} sessions
            </p>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={isLoading}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isLoading}>
              {isLoading ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <CreditCard className="mr-2 h-4 w-4" />
              )}
              Pay ₹{amount ?? 0}
            </Button>
          </DialogFooter>
        </form>

        {polling && (
          <p className="flex items-center gap-2 text-xs text-muted-foreground">
            <RotateCcw className="h-3 w-3 animate-spin" />
            Waiting for payment confirmation…
          </p>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export function BillingPage() {
  const [topupOpen, setTopupOpen] = useState(false);
  const [page, setPage] = useState(0);

  const { data: wallet, isLoading: walletLoading } = useQuery({
    queryKey: queryKeys.billing.wallet(),
    queryFn: billingApi.getWallet,
  });

  const { data: txPage, isLoading: txLoading } = useQuery({
    queryKey: queryKeys.billing.transactions({ page }),
    queryFn: () => billingApi.getTransactions({ page, size: PAGE_SIZE }),
  });

  const transactions = txPage?.content ?? [];
  const totalPages = txPage?.totalPages ?? 0;

  const txLabel: Record<TransactionType, string> = {
    PROMO_CREDIT: "Promotional credit",
  PROMO_EXPIRY: "Promotional credit expired",
  TOPUP: "Wallet Top-up",
    RESERVATION: "Session Reserved",
    SETTLEMENT: "Interview Session",
    RELEASE: "Reservation Released",
    REFUND: "Refund",
  };

  const isCredit = (type: TransactionType) =>
    type === "TOPUP" || type === "REFUND" || type === "RELEASE";

  return (
    <div className="space-y-6">
      <PageHeader
        title="Billing"
        description="Manage your wallet balance and view transaction history."
        actions={
          <Button onClick={() => setTopupOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            Top Up
          </Button>
        }
      />

      {/* ── Wallet cards ─────────────────────────────────────────────────── */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {/* Balance */}
        <Card className="lg:col-span-1">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">
              Current Balance
            </CardTitle>
            <TrendingUp className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {walletLoading ? (
              <div className="h-9 w-32 animate-pulse rounded bg-muted" />
            ) : (
              wallet && <WalletBalance wallet={wallet} />
            )}
            <Button
              className="mt-4 w-full"
              size="sm"
              onClick={() => setTopupOpen(true)}
            >
              <Plus className="mr-2 h-3.5 w-3.5" />
              Add Funds
            </Button>
          </CardContent>
        </Card>

        {/* Pricing info */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-sm font-medium">Pricing</CardTitle>
          </CardHeader>
          <Separator />
          <CardContent className="pt-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-2xl font-bold">₹50</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  per AI interview session
                </p>
              </div>
              <div className="space-y-1.5 text-sm text-muted-foreground">
                <p>✓ AI-generated interview questions</p>
                <p>✓ Bot joins your video call</p>
                <p>✓ Full transcript + evaluation</p>
                <p>✓ Radar chart skill analysis</p>
                <p>✓ Hire / Hold / Reject recommendation</p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ── Transaction history ───────────────────────────────────────────── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Transaction History</CardTitle>
        </CardHeader>
        <Separator />
        <CardContent className="p-0">
          {txLoading ? (
            <div className="space-y-2 p-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-14 animate-pulse rounded bg-muted" />
              ))}
            </div>
          ) : transactions.length === 0 ? (
            <div className="py-10 text-center text-sm text-muted-foreground">
              No transactions yet. Top up your wallet to get started.
            </div>
          ) : (
            <ul className="divide-y">
              {transactions.map((tx) => (
                <li
                  key={tx.id}
                  className="flex items-center gap-4 px-6 py-4"
                >
                  <TxIcon type={tx.transactionType} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">
                      {txLabel[tx.transactionType] ?? tx.transactionType}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {formatDate(tx.createdAt)}
                    </p>
                  </div>
                  <div className="text-right">
                    <p
                      className={
                        isCredit(tx.transactionType)
                          ? "font-semibold text-green-600"
                          : "font-semibold text-red-600"
                      }
                    >
                      {isCredit(tx.transactionType) ? "+" : "-"}
                      {formatRupees(Math.abs(tx.amountPaise))}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Bal: {formatRupees(tx.balanceAfterPaise)}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            Page {page + 1} of {totalPages}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p - 1)}
              disabled={page === 0}
            >
              <ChevronLeft className="h-4 w-4" />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage((p) => p + 1)}
              disabled={page >= totalPages - 1}
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      )}

      <TopupDialog open={topupOpen} onOpenChange={setTopupOpen} />
    </div>
  );
}
