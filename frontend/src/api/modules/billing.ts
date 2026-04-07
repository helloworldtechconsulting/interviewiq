import { apiClient } from "@/api/client";
import type {
  CreateOrderRequest,
  Page,
  RazorpayOrder,
  Wallet,
  WalletTransaction,
} from "@/types";

const BASE = "/api/v1/billing";

export const billingApi = {
  getWallet: () =>
    apiClient.get<Wallet>(`${BASE}/wallet`).then((r) => r.data),

  getTransactions: (params?: { page?: number; size?: number }) =>
    apiClient
      .get<Page<WalletTransaction>>(`${BASE}/transactions`, { params })
      .then((r) => r.data),

  // POST /api/v1/billing/topup — creates a Razorpay order
  createOrder: (data: CreateOrderRequest) =>
    apiClient
      .post<RazorpayOrder>(`${BASE}/topup`, data)
      .then((r) => r.data),
};
