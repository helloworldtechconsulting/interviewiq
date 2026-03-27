import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Plus, AlertCircle, Mail } from 'lucide-react';
import { useJobs } from '../hooks/useJobs';
import { StatusBadge } from '../components/StatusBadge';
import { ScoreBadge } from '../components/ScoreBadge';
import { FileUpload } from '../components/FileUpload';
import { LoadingSpinner } from '../components/LoadingSpinner';

export const JobDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { getJobById, getJobCandidates, addCandidate } = useJobs();
  const { data: job, isLoading: jobLoading } = getJobById(id || '');
  const { data: candidates, isLoading: candidatesLoading } = getJobCandidates(id || '');

  const [showAddCandidate, setShowAddCandidate] = useState(false);
  const [candidateData, setCandidateData] = useState({
    name: '',
    email: '',
    phone: '',
  });
  const [resumeFile, setResumeFile] = useState<File | null>(null);
  const [error, setError] = useState('');

  const handleAddCandidate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!candidateData.name || !candidateData.email || !candidateData.phone) {
      setError('Please fill in all required fields');
      return;
    }

    try {
      await addCandidate.mutateAsync({
        jobOpeningId: id!,
        ...candidateData,
        resumeFile: resumeFile || undefined,
      });
      setCandidateData({ name: '', email: '', phone: '' });
      setResumeFile(null);
      setShowAddCandidate(false);
    } catch (err: any) {
      setError(
        err.response?.data?.message || 'Failed to add candidate. Please try again.'
      );
    }
  };

  if (jobLoading || candidatesLoading) return <LoadingSpinner />;

  if (!job) {
    return (
      <div className="text-center py-12">
        <AlertCircle size={48} className="mx-auto text-red-400 mb-4" />
        <p className="text-gray-900 font-medium">Job opening not found</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Back Button */}
      <button
        onClick={() => navigate('/jobs')}
        className="flex items-center gap-2 text-blue-500 hover:text-blue-600 font-medium"
      >
        <ArrowLeft size={20} />
        Back to Jobs
      </button>

      {/* Job Header */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">{job.title}</h1>
            <p className="text-gray-600 mt-1">{job.department}</p>
          </div>
          <StatusBadge status={job.status} />
        </div>

        <p className="text-gray-700 mb-6">{job.description}</p>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <p className="text-gray-600 text-sm">Location</p>
            <p className="font-semibold text-gray-900">{job.locationType}</p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Employment Type</p>
            <p className="font-semibold text-gray-900">{job.employmentType}</p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Interviews</p>
            <p className="font-semibold text-gray-900">{job.interviewCount}</p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Avg Score</p>
            <p className="font-semibold text-gray-900">
              {Math.round(job.averageScore)}
            </p>
          </div>
        </div>
      </div>

      {/* Candidates Section */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-6">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-2xl font-bold text-gray-900">Candidates</h2>
          <button
            onClick={() => setShowAddCandidate(!showAddCandidate)}
            className="flex items-center gap-2 px-4 py-2 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
          >
            <Plus size={20} />
            Add Candidate
          </button>
        </div>

        {/* Add Candidate Form */}
        {showAddCandidate && (
          <div className="mb-6 p-6 bg-gray-50 rounded-lg border border-gray-200">
            {error && (
              <div className="mb-4 p-4 bg-red-50 border border-red-200 rounded-lg flex gap-3">
                <AlertCircle size={20} className="text-red-600 flex-shrink-0" />
                <p className="text-red-700 text-sm">{error}</p>
              </div>
            )}
            <form onSubmit={handleAddCandidate} className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <input
                  type="text"
                  placeholder="Full Name"
                  value={candidateData.name}
                  onChange={(e) =>
                    setCandidateData((prev) => ({ ...prev, name: e.target.value }))
                  }
                  required
                  className="px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <input
                  type="email"
                  placeholder="Email"
                  value={candidateData.email}
                  onChange={(e) =>
                    setCandidateData((prev) => ({ ...prev, email: e.target.value }))
                  }
                  required
                  className="px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              <input
                type="tel"
                placeholder="Phone"
                value={candidateData.phone}
                onChange={(e) =>
                  setCandidateData((prev) => ({ ...prev, phone: e.target.value }))
                }
                required
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />

              <FileUpload
                onFileSelect={setResumeFile}
                accept=".pdf,.doc,.docx"
                label="Resume (Optional)"
              />

              <div className="flex gap-4">
                <button
                  type="submit"
                  disabled={addCandidate.isPending}
                  className="flex-1 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-400 text-white font-medium py-2 rounded-lg transition-colors"
                >
                  {addCandidate.isPending ? 'Adding...' : 'Add Candidate'}
                </button>
                <button
                  type="button"
                  onClick={() => setShowAddCandidate(false)}
                  className="flex-1 bg-gray-200 hover:bg-gray-300 text-gray-900 font-medium py-2 rounded-lg transition-colors"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Candidates List */}
        {!candidates || candidates.length === 0 ? (
          <div className="text-center py-8">
            <Mail size={40} className="mx-auto text-gray-300 mb-2" />
            <p className="text-gray-600">No candidates yet</p>
            <p className="text-gray-500 text-sm mt-1">
              Add candidates to start conducting interviews
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-gray-200">
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Name
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Email
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Phone
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Status
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Score
                  </th>
                  <th className="text-left py-3 px-4 font-semibold text-gray-900">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {candidates.map((candidate) => (
                  <tr
                    key={candidate.id}
                    className="border-b border-gray-200 hover:bg-gray-50"
                  >
                    <td className="py-4 px-4 text-gray-900 font-medium">
                      {candidate.name}
                    </td>
                    <td className="py-4 px-4 text-gray-600">{candidate.email}</td>
                    <td className="py-4 px-4 text-gray-600">{candidate.phone}</td>
                    <td className="py-4 px-4">
                      <StatusBadge status={candidate.status} variant="small" />
                    </td>
                    <td className="py-4 px-4">
                      {candidate.score !== undefined ? (
                        <span className="font-semibold text-gray-900">
                          {Math.round(candidate.score)}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                    <td className="py-4 px-4">
                      <button
                        onClick={() => navigate(`/sessions/${candidate.id}`)}
                        className="text-blue-500 hover:text-blue-600 text-sm font-medium"
                      >
                        View
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
