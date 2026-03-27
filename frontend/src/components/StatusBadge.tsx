import { JobStatus, CandidateStatus, InterviewStatus } from '../types';

interface StatusBadgeProps {
  status: JobStatus | CandidateStatus | InterviewStatus;
  variant?: 'default' | 'small';
}

const statusStyles: Record<string, { bg: string; text: string }> = {
  DRAFT: { bg: 'bg-gray-100', text: 'text-gray-800' },
  ACTIVE: { bg: 'bg-green-100', text: 'text-green-800' },
  CLOSED: { bg: 'bg-red-100', text: 'text-red-800' },
  INVITED: { bg: 'bg-blue-100', text: 'text-blue-800' },
  SCHEDULED: { bg: 'bg-indigo-100', text: 'text-indigo-800' },
  'IN_PROGRESS': { bg: 'bg-yellow-100', text: 'text-yellow-800' },
  COMPLETED: { bg: 'bg-green-100', text: 'text-green-800' },
  REJECTED: { bg: 'bg-red-100', text: 'text-red-800' },
  FAILED: { bg: 'bg-red-100', text: 'text-red-800' },
};

export const StatusBadge = ({ status, variant = 'default' }: StatusBadgeProps) => {
  const style = statusStyles[status] || statusStyles.DRAFT;
  const sizeClass = variant === 'small' ? 'px-2 py-1 text-xs' : 'px-3 py-1 text-sm';

  return (
    <span className={`${style.bg} ${style.text} rounded-full font-medium ${sizeClass}`}>
      {status.replace('_', ' ')}
    </span>
  );
};
