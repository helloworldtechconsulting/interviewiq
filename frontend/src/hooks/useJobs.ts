import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import api from '../api/client';
import { JobOpening, Candidate, AvailabilitySlot, ApiResponse } from '../types';

export interface CreateJobPayload {
  title: string;
  department: string;
  locationType: 'REMOTE' | 'HYBRID' | 'ONSITE';
  employmentType: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT';
  description: string;
  jdFile?: File;
}

export interface AddCandidatePayload {
  jobOpeningId: string;
  name: string;
  email: string;
  phone: string;
  resumeFile?: File;
}

export interface CreateSlotsPayload {
  jobId: string;
  slots: {
    startTime: string;
    endTime: string;
    maxInterviews: number;
  }[];
}

export const useJobs = () => {
  const queryClient = useQueryClient();

  const getJobs = useQuery({
    queryKey: ['jobs'],
    queryFn: () => api.get<ApiResponse<JobOpening[]>>('/api/v1/jobs'),
    select: (response) => response.data.data,
  });

  const getJobById = (jobId: string) =>
    useQuery({
      queryKey: ['job', jobId],
      queryFn: () => api.get<ApiResponse<JobOpening>>(`/api/v1/jobs/${jobId}`),
      select: (response) => response.data.data,
      enabled: !!jobId,
    });

  const getJobCandidates = (jobId: string) =>
    useQuery({
      queryKey: ['job-candidates', jobId],
      queryFn: () => api.get<ApiResponse<Candidate[]>>(`/api/v1/jobs/${jobId}/candidates`),
      select: (response) => response.data.data,
      enabled: !!jobId,
    });

  const getJobSlots = (jobId: string) =>
    useQuery({
      queryKey: ['job-slots', jobId],
      queryFn: () => api.get<ApiResponse<AvailabilitySlot[]>>(`/api/v1/jobs/${jobId}/slots`),
      select: (response) => response.data.data,
      enabled: !!jobId,
    });

  const createJob = useMutation({
    mutationFn: async (payload: CreateJobPayload) => {
      const formData = new FormData();
      formData.append('title', payload.title);
      formData.append('department', payload.department);
      formData.append('locationType', payload.locationType);
      formData.append('employmentType', payload.employmentType);
      formData.append('description', payload.description);
      if (payload.jdFile) {
        formData.append('jdFile', payload.jdFile);
      }

      return api.post<ApiResponse<JobOpening>>('/api/v1/jobs', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
    },
  });

  const updateJob = useMutation({
    mutationFn: ({ jobId, ...data }: { jobId: string } & Partial<CreateJobPayload>) =>
      api.put<ApiResponse<JobOpening>>(`/api/v1/jobs/${jobId}`, data),
    onSuccess: (_, { jobId }) => {
      queryClient.invalidateQueries({ queryKey: ['job', jobId] });
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
    },
  });

  const addCandidate = useMutation({
    mutationFn: async (payload: AddCandidatePayload) => {
      const formData = new FormData();
      formData.append('jobOpeningId', payload.jobOpeningId);
      formData.append('name', payload.name);
      formData.append('email', payload.email);
      formData.append('phone', payload.phone);
      if (payload.resumeFile) {
        formData.append('resumeFile', payload.resumeFile);
      }

      return api.post<ApiResponse<Candidate>>('/api/v1/candidates', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });
    },
    onSuccess: (_, { jobOpeningId }) => {
      queryClient.invalidateQueries({ queryKey: ['job-candidates', jobOpeningId] });
    },
  });

  const createSlots = useMutation({
    mutationFn: ({ jobId, slots }: CreateSlotsPayload) =>
      api.post<ApiResponse<AvailabilitySlot[]>>(`/api/v1/jobs/${jobId}/slots`, { slots }),
    onSuccess: (_, { jobId }) => {
      queryClient.invalidateQueries({ queryKey: ['job-slots', jobId] });
    },
  });

  return {
    getJobs,
    getJobById,
    getJobCandidates,
    getJobSlots,
    createJob,
    updateJob,
    addCandidate,
    createSlots,
  };
};
