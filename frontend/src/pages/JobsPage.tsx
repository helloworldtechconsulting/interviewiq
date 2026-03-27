import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, BarChart3, AlertCircle } from 'lucide-react';
import { useJobs } from '../hooks/useJobs';
import { StatusBadge } from '../components/StatusBadge';
import { LoadingSpinner } from '../components/LoadingSpinner';

export const JobsPage = () => {
  const navigate = useNavigate();
  const { data: jobs, isLoading } = useJobs().getJobs;
  const [filter, setFilter] = useState<'ALL' | 'DRAFT' | 'ACTIVE' | 'CLOSED'>('ALL');

  if (isLoading) return <LoadingSpinner />;

  const filteredJobs =
    filter === 'ALL'
      ? jobs || []
      : (jobs || []).filter((job) => job.status === filter);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Job Openings</h1>
          <p className="text-gray-600 mt-1">Manage your job postings and candidates</p>
        </div>
        <button
          onClick={() => navigate('/jobs/create')}
          className="flex items-center gap-2 px-6 py-3 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
        >
          <Plus size={20} />
          Create Job Opening
        </button>
      </div>

      {/* Filters */}
      <div className="flex gap-2 flex-wrap">
        {(['ALL', 'DRAFT', 'ACTIVE', 'CLOSED'] as const).map((status) => (
          <button
            key={status}
            onClick={() => setFilter(status)}
            className={`px-4 py-2 rounded-lg font-medium transition-colors ${
              filter === status
                ? 'bg-blue-500 text-white'
                : 'bg-white text-gray-700 border border-gray-200 hover:border-gray-300'
            }`}
          >
            {status}
          </button>
        ))}
      </div>

      {/* Jobs List */}
      {filteredJobs.length === 0 ? (
        <div className="text-center py-12 bg-white rounded-lg border border-gray-200">
          <AlertCircle size={48} className="mx-auto text-gray-300 mb-4" />
          <p className="text-gray-500 font-medium">No job openings found</p>
          <p className="text-gray-400 text-sm mt-1">
            {filter === 'ALL'
              ? 'Create your first job opening to get started'
              : `No ${filter.toLowerCase()} job openings`}
          </p>
          {filter === 'ALL' && (
            <button
              onClick={() => navigate('/jobs/create')}
              className="mt-4 px-6 py-2 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
            >
              Create Job Opening
            </button>
          )}
        </div>
      ) : (
        <div className="grid gap-6">
          {filteredJobs.map((job) => (
            <div
              key={job.id}
              className="bg-white rounded-lg shadow-sm border border-gray-100 hover:shadow-md transition-shadow cursor-pointer"
              onClick={() => navigate(`/jobs/${job.id}`)}
            >
              <div className="p-6">
                <div className="flex items-start justify-between mb-4">
                  <div className="flex-1">
                    <h2 className="text-2xl font-bold text-gray-900">{job.title}</h2>
                    <p className="text-gray-600 mt-1">{job.department}</p>
                  </div>
                  <StatusBadge status={job.status} />
                </div>

                <p className="text-gray-600 text-sm mb-4 line-clamp-2">
                  {job.description}
                </p>

                <div className="flex flex-wrap gap-4 text-sm">
                  <div className="flex items-center gap-2 text-gray-600">
                    <span className="font-medium">Location:</span>
                    <span>{job.locationType}</span>
                  </div>
                  <div className="flex items-center gap-2 text-gray-600">
                    <span className="font-medium">Type:</span>
                    <span>{job.employmentType}</span>
                  </div>
                </div>

                <div className="flex items-center gap-6 mt-6 pt-6 border-t border-gray-200">
                  <div>
                    <p className="text-gray-600 text-sm">Interviews</p>
                    <p className="text-2xl font-bold text-gray-900">
                      {job.interviewCount}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <BarChart3 size={18} className="text-blue-500" />
                    <div>
                      <p className="text-gray-600 text-sm">Avg Score</p>
                      <p className="text-2xl font-bold text-gray-900">
                        {Math.round(job.averageScore)}
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
