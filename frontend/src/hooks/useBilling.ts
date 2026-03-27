import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import api from '../api/client';
import { BillingInfo, BillingTransaction, ApiResponse } from '../types';

export interface InitiateTopupPayload {
  amountPaise: number;
}

export interface VerifyTopupPayload {
  razorpayOrderId: string;
  razorpayPaymentId: string;
  razorpaySignature: string;
}

export const useBilling = () => {
  const queryClient = useQueryClient();

  const getBalance = useQuery({
    queryKey: ['billing-balance'],
    queryFn: () => api.get<ApiResponse<BillingInfo>>('/api/v1/billing/balance'),
    select: (response) => response.data.data,
  });

  const getTransactions = useQuery({
    queryKey: ['billing-transactions'],
    queryFn: () => api.get<ApiResponse<BillingTransaction[]>>('/api/v1/billing/transactions'),
    select: (response) => response.data.data,
  });

  const initiateTopup = useMutation({
    mutationFn: (payload: InitiateTopupPayload) =>
      api.post<
        ApiResponse<{
          orderId: string;
          amount: number;
          currency: string;
          key: string;
        }>
      >('/api/v1/billing/topup/initiate', payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing-balance'] });
    },
  });

  const verifyTopup = useMutation({
    mutationFn: (payload: VerifyTopupPayload) =>
      api.post<ApiResponse<BillingTransaction>>('/api/v1/billing/topup/verify', payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing-balance'] });
      queryClient.invalidateQueries({ queryKey: ['billing-transactions'] });
    },
  });

  return {
    getBalance,
    getTransactions,
    initiateTopup,
    verifyTopup,
  };
};
