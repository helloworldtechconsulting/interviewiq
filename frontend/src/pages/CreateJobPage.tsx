import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, AlertCircle } from 'lucide-react';
import { useJobs } from '../hooks/useJobs';
import { FileUpload } from '../components/FileUpload';

export const CreateJobPage = () => {
  const navigate = useNavigate();
  const { createJob } = useJobs();
  const [error, setError] = useState('');
  const [formData, setFormData] = useState({
    title: '',
    department: '',
    locationType: 'REMOTE' as const,
    employmentType: 'FULL_TIME' as const,
    description: '',
  });
  const [jdFile, setJdFile] = useState<File | null>(null);

  const handleChange = (
    e: React.ChangeEvent<
      HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
    >
  ) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!formData.title || !formData.department || !formData.description) {
      setError('Please fill in all required fields');
      return;
    }

    try {
      await createJob.mutateAsync({
        ...formData,
        jdFile: jdFile || undefined,
      });
      navigate('/jobs');
    } catch (err: any) {
      setError(
        err.response?.data?.message || 'Failed to create job opening. Please try again.'
      );
    }
  };

  return (
    <div className="max-w-3xl mx-auto">
      <button
        onClick={() => navigate('/jobs')}
        className="flex items-center gap-2 text-blue-500 hover:text-blue-600 font-medium mb-6"
      >
        <ArrowLeft size={20} />
        Back to Job Openings
      </button>

      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Create Job Opening</h1>
        <p className="text-gray-600 mb-8">
          Post a new job opening and start recruiting with AI-powered interviews
        </p>

        {error && (
          <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-lg flex gap-3">
            <AlertCircle size={20} className="text-red-600 flex-shrink-0" />
            <p className="text-red-700 text-sm">{error}</p>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Title */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Job Title *
            </label>
            <input
              type="text"
              name="title"
              value={formData.title}
              onChange={handleChange}
              required
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="e.g., Senior React Developer"
            />
          </div>

          {/* Department */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Department *
            </label>
            <input
              type="text"
              name="department"
              value={formData.department}
              onChange={handleChange}
              required
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="e.g., Engineering"
            />
          </div>

          {/* Location Type */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Location Type *
            </label>
            <select
              name="locationType"
              value={formData.locationType}
              onChange={handleChange}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="REMOTE">Remote</option>
              <option value="HYBRID">Hybrid</option>
              <option value="ONSITE">On-site</option>
            </select>
          </div>

          {/* Employment Type */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Employment Type *
            </label>
            <select
              name="employmentType"
              value={formData.employmentType}
              onChange={handleChange}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="FULL_TIME">Full Time</option>
              <option value="PART_TIME">Part Time</option>
              <option value="CONTRACT">Contract</option>
            </select>
          </div>

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Job Description *
            </label>
            <textarea
              name="description"
              value={formData.description}
              onChange={handleChange}
              required
              rows={6}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="Describe the role, responsibilities, and requirements..."
            ></textarea>
          </div>

          {/* JD File Upload */}
          <FileUpload
            onFileSelect={setJdFile}
            accept=".pdf,.doc,.docx"
            maxSizeMB={10}
            label="Job Description File (Optional)"
          />

          {/* Submit Button */}
          <div className="flex gap-4 pt-6">
            <button
              type="submit"
              disabled={createJob.isPending}
              className="flex-1 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-400 text-white font-medium py-3 rounded-lg transition-colors"
            >
              {createJob.isPending ? 'Creating...' : 'Create Job Opening'}
            </button>
            <button
              type="button"
              onClick={() => navigate('/jobs')}
              className="flex-1 bg-gray-200 hover:bg-gray-300 text-gray-900 font-medium py-3 rounded-lg transition-colors"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
