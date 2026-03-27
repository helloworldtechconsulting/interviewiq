import { useQuery, useMutation } from '@tanstack/react-query';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';
import { User, AuthTokens, ApiResponse } from '../types';

export interface LoginPayload {
  email: string;
  password: string;
}

export interface RegisterPayload {
  companyName: string;
  name: string;
  email: string;
  password: string;
}

export interface CandidateVerifyPayload {
  token: string;
}

export const useAuth = () => {
  const { setAuth, logout, user, tokens } = useAuthStore();

  const login = useMutation({
    mutationFn: (payload: LoginPayload) =>
      api.post<ApiResponse<{ user: User; tokens: AuthTokens }>>('/api/auth/login', payload),
    onSuccess: (response) => {
      const { user, tokens } = response.data.data;
      setAuth(user, tokens);
    },
  });

  const register = useMutation({
    mutationFn: (payload: RegisterPayload) =>
      api.post<ApiResponse<{ user: User; tokens: AuthTokens }>>('/api/auth/register', payload),
    onSuccess: (response) => {
      const { user, tokens } = response.data.data;
      setAuth(user, tokens);
    },
  });

  const verifyCandidate = useMutation({
    mutationFn: (payload: CandidateVerifyPayload) =>
      api.post<
        ApiResponse<{
          sessionId: string;
          jobOpeningId: string;
          candidateName: string;
          companyName: string;
          jobTitle: string;
          instructions: string;
          totalQuestions: number;
          maxDurationMinutes: number;
          accessToken: string;
        }>
      >('/api/auth/candidate/verify', payload),
  });

  return {
    user,
    tokens,
    login,
    register,
    verifyCandidate,
    logout: () => logout(),
    isAuthenticated: !!tokens?.accessToken,
  };
};
