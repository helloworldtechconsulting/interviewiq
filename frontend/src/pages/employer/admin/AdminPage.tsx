import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { PageHeader } from "@/components/common/PageHeader";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { apiClient } from "@/api/client";

const adminApi = {
    manualCredit: (companyId: string, amountPaise: number, reason: string) =>
        apiClient
            .post(`/api/v1/admin/companies/${companyId}/wallet/credit`, {
                amountPaise,
                reason,
            })
            .then((r) => r.data),
};

export function AdminPage() {
    const [companyId, setCompanyId] = useState("");
    const [amount, setAmount] = useState("");
    const [reason, setReason] = useState("");

    const creditMutation = useMutation({
        mutationFn: () =>
            adminApi.manualCredit(
                companyId,
                Math.round(parseFloat(amount) * 100),
                reason
            ),
        onSuccess: () => {
            toast.success("Wallet credited successfully!");
            setCompanyId("");
            setAmount("");
            setReason("");
        },
        onError: () => {
            toast.error("Failed to credit wallet.");
        },
    });

    function handleSubmit() {
        if (!companyId || !amount || !reason) {
            toast.error("Please fill all fields!");
            return;
        }
        creditMutation.mutate();
    }

    return (
        <div className="space-y-6">
            <PageHeader
                title="Admin Panel"
                description="Manually credit company wallets for refunds or promotional credits."
            />
            <Card className="max-w-lg">
                <CardHeader>
                    <CardTitle className="text-base">Manual Wallet Credit</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                    <div className="space-y-2">
                        <Label>Company ID</Label>
                        <Input
                            placeholder="Enter company UUID"
                            value={companyId}
                            onChange={(e) => setCompanyId(e.target.value)}
                        />
                    </div>
                    <div className="space-y-2">
                        <Label>Amount (₹)</Label>
                        <Input
                            type="number"
                            placeholder="e.g. 500"
                            value={amount}
                            onChange={(e) => setAmount(e.target.value)}
                            min="1"
                        />
                    </div>
                    <div className="space-y-2">
                        <Label>Reason (mandatory)</Label>
                        <Input
                            placeholder="e.g. Refund for failed interview"
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                        />
                    </div>
                    <Button
                        className="w-full"
                        onClick={handleSubmit}
                        disabled={creditMutation.isPending || !companyId || !amount || !reason}
                    >
                        {creditMutation.isPending ? "Processing..." : "Credit Wallet"}
                    </Button>
                </CardContent>
            </Card>
        </div>
    );
}