import { useNavigate } from 'react-router-dom';
import {
  Briefcase,
  CheckCircle,
  Clock,
  BarChart3,
  Plus,
  CreditCard,
  TrendingUp,
} from 'lucide-react';
import { useJobs } from '../hooks/useJobs';
import { useBilling } from '../hooks/useBilling';
import { ScoreBadge } from '../components/ScoreBadge';
import { StatusBadge } from '../components/StatusBadge';
import { LoadingSpinner } from '../components/LoadingSpinner';

export const DashboardPage = () => {
  const navigate = useNavigate();
  const { data: jobs, isLoading: jobsLoading } = useJobs().getJobs;
  const { data: billingInfo } = useBilling().getBalance;

  if (jobsLoading) return <LoadingSpinner />;

  const activeJobs = jobs?.filter((j) => j.status === 'ACTIVE').length || 0;
  const totalInterviews = jobs?.reduce((sum, j) => sum + j.interviewCount, 0) || 0;
  const avgScore =
    jobs && jobs.length > 0
      ? Math.round(
          jobs.reduce((sum, j) => sum + j.averageScore, 0) / jobs.length
        )
      : 0;

  const walletBalance = billingInfo
    ? (billingInfo.balancePaise / 100).toFixed(2)
    : '0.00';

  const summaryCards = [
    {
      icon: Briefcase,
      label: 'Active Jobs',
      value: activeJobs.toString(),
      color: 'bg-blue-500',
    },
    {
      icon: BarChart3,
      label: 'Total Interviews',
      value: totalInterviews.toString(),
      color: 'bg-green-500',
    },
    {
      icon: CheckCircle,
      label: 'Average Score',
      value: avgScore.toString(),
      color: 'bg-purple-500',
    },
    {
      icon: CreditCard,
      label: 'Wallet Balance',
      value: `₹${walletBalance}`,
      color: 'bg-orange-500',
    },
  ];

  return (
    <div className="space-y-8">
      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {summaryCards.map((card, idx) => (
          <div
            key={idx}
            className="bg-white rounded-lg shadow-sm p-6 border border-gray-100"
          >
            <div className="flex items-start justify-between">
              <div>
                <p className="text-gray-600 text-sm font-medium">{card.label}</p>
                <p className="text-3xl font-bold text-gray-900 mt-2">
                  {card.value}
                </p>
              </div>
              <div className={`${card.color} p-3 rounded-lg`}>
                <card.icon size={24} className="text-white" />
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Quick Actions */}
      <div className="bg-white rounded-lg shadow-sm p-6 border border-gray-100">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Quick Actions</h2>
        <div className="flex flex-col sm:flex-row gap-4">
          <button
            onClick={() => navigate('/jobs/create')}
            className="flex items-center justify-center gap-2 px-6 py-3 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
          >
            <Plus size={20} />
            Create Job Opening
          </button>
          <button
            onClick={() => navigate('/billing')}
            className="flex items-center justify-center gap-2 px-6 py-3 bg-green-500 hover:bg-green-600 text-white rounded-lg font-medium transition-colors"
          >
            <TrendingUp size={20} />
            Top Up Wallet
          </button>
        </div>
      </div>

      {/* Recent Jobs */}
      <div className="bg-white rounded-lg shadow-sm p-6 border border-gray-100">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-semibold text-gray-900">Recent Job Openings</h2>
          <button
            onClick={() => navigate('/jobs')}
            className="text-blue-500 hover:text-blue-600 font-medium text-sm"
          >
            View all
          </button>
        </div>

        {!jobs || jobs.length === 0 ? (
          <div className="text-center py-12">
            <Briefcase size={48} className="mx-auto text-gray-300 mb-4" />
            <p className="text-gray-500 font-medium">No job openings yet</p>
            <p className="text-gray-400 text-sm mt-1">
              Create your first job opening to get started
            </p>
            <button
              onClick={() => navigate('/jobs/create')}
              className="mt-4 px-6 py-2 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
            >
              Create Job Opening
            </button>
          </div>
        ) : (
          <div className="space-y-4">
            {jobs.slice(0, 5).map((job) => (
              <div
                key={job.id}
                className="p-4 border border-gray-200 rounded-lg hover:border-gray-300 cursor-pointer transition-colors"
                onClick={() => navigate(`/jobs/${job.id}`)}
              >
                <div className="flex items-start justify-between">
                  <div>
                    <h3 className="font-semibold text-gray-900">{job.title}</h3>
                    <p className="text-sm text-gray-600 mt-1">{job.department}</p>
                  </div>
                  <StatusBadge status={job.status} />
                </div>
                <div className="flex items-center gap-4 mt-4 text-sm text-gray-600">
                  <div className="flex items-center gap-1">
                    <BarChart3 size={16} />
                    {job.interviewCount} interviews
                  </div>
                  <div className="flex items-center gap-2">
                    Avg Score:{' '}
                    <span className="font-semibold text-gray-900">
                      {Math.round(job.averageScore)}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
