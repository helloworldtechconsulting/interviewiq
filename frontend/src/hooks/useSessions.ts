import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import api from '../api/client';
import { InterviewSession, EvaluationReport, ApiResponse } from '../types';

export interface UpdateNotesPayload {
  sessionId: string;
  notes: string;
}

export interface BookSlotPayload {
  sessionId: string;
  slotId: string;
}

export const useSessions = () => {
  const queryClient = useQueryClient();

  const getSession = (sessionId: string) =>
    useQuery({
      queryKey: ['session', sessionId],
      queryFn: () => api.get<ApiResponse<InterviewSession>>(`/api/v1/sessions/${sessionId}`),
      select: (response) => response.data.data,
      enabled: !!sessionId,
    });

  const getSessionReport = (sessionId: string) =>
    useQuery({
      queryKey: ['session-report', sessionId],
      queryFn: () =>
        api.get<ApiResponse<EvaluationReport>>(`/api/v1/sessions/${sessionId}/report`),
      select: (response) => response.data.data,
      enabled: !!sessionId,
    });

  const updateNotes = useMutation({
    mutationFn: ({ sessionId, notes }: UpdateNotesPayload) =>
      api.post<ApiResponse<InterviewSession>>(`/api/v1/sessions/${sessionId}/notes`, {
        notes,
      }),
    onSuccess: (_, { sessionId }) => {
      queryClient.invalidateQueries({ queryKey: ['session', sessionId] });
    },
  });

  const resendInvite = useMutation({
    mutationFn: (sessionId: string) =>
      api.post<ApiResponse<{ message: string }>>(`/api/v1/sessions/${sessionId}/resend`),
  });

  const bookSlot = useMutation({
    mutationFn: ({ sessionId, slotId }: BookSlotPayload) =>
      api.post<ApiResponse<InterviewSession>>(`/api/v1/sessions/${sessionId}/book`, {
        slotId,
      }),
    onSuccess: (_, { sessionId }) => {
      queryClient.invalidateQueries({ queryKey: ['session', sessionId] });
    },
  });

  return {
    getSession,
    getSessionReport,
    updateNotes,
    resendInvite,
    bookSlot,
  };
};
