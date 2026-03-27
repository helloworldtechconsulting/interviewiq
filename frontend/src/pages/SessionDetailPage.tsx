import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  AlertCircle,
  Play,
  ChevronDown,
  ChevronUp,
  Clock,
} from 'lucide-react';
import { useSessions } from '../hooks/useSessions';
import { ScoreBadge } from '../components/ScoreBadge';
import { DimensionChart } from '../components/DimensionChart';
import { LoadingSpinner } from '../components/LoadingSpinner';
import { format } from 'date-fns';

export const SessionDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { getSession, getSessionReport, updateNotes } = useSessions();
  const { data: session, isLoading: sessionLoading } = getSession(id || '');
  const { data: report, isLoading: reportLoading } = getSessionReport(id || '');

  const [notes, setNotes] = useState(session?.notes || '');
  const [isEditingNotes, setIsEditingNotes] = useState(false);
  const [expandedQuestions, setExpandedQuestions] = useState<number[]>([]);

  const handleSaveNotes = async () => {
    if (!id) return;
    try {
      await updateNotes.mutateAsync({ sessionId: id, notes });
      setIsEditingNotes(false);
    } catch (err) {
      console.error('Failed to save notes');
    }
  };

  const toggleQuestion = (index: number) => {
    setExpandedQuestions((prev) =>
      prev.includes(index) ? prev.filter((i) => i !== index) : [...prev, index]
    );
  };

  if (sessionLoading || reportLoading) return <LoadingSpinner />;

  if (!session || !report) {
    return (
      <div className="text-center py-12">
        <AlertCircle size={48} className="mx-auto text-red-400 mb-4" />
        <p className="text-gray-900 font-medium">Session not found</p>
      </div>
    );
  }

  const duration = session.duration
    ? Math.floor(session.duration / 60) + ' min'
    : 'N/A';

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

      {/* Score Header */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">Interview Session</h1>
            <p className="text-gray-600 mt-1">Comprehensive evaluation report</p>
          </div>
          <ScoreBadge score={report.overallScore} size="large" />
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <p className="text-gray-600 text-sm">Status</p>
            <p className="font-semibold text-gray-900 capitalize">
              {session.status.replace(/_/g, ' ')}
            </p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Duration</p>
            <p className="font-semibold text-gray-900">{duration}</p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Date</p>
            <p className="font-semibold text-gray-900">
              {session.startTime
                ? format(new Date(session.startTime), 'MMM dd, yyyy')
                : 'N/A'}
            </p>
          </div>
          <div>
            <p className="text-gray-600 text-sm">Recommendation</p>
            <p className="font-semibold text-gray-900">
              {report.recommendation.replace(/_/g, ' ')}
            </p>
          </div>
        </div>
      </div>

      {/* Dimension Scores */}
      {report.dimensionScores && report.dimensionScores.length > 0 && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
          <h2 className="text-2xl font-bold text-gray-900 mb-6">
            Dimension Scores
          </h2>
          <DimensionChart data={report.dimensionScores} />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-6">
            {report.dimensionScores.map((dim) => (
              <div
                key={dim.dimension}
                className="p-4 border border-gray-200 rounded-lg"
              >
                <div className="flex items-center justify-between mb-2">
                  <p className="font-semibold text-gray-900">{dim.dimension}</p>
                  <span className="text-lg font-bold text-blue-500">
                    {Math.round(dim.score)}
                  </span>
                </div>
                <p className="text-gray-600 text-sm">{dim.feedback}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Transcript */}
      {report.transcript && report.transcript.length > 0 && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
          <h2 className="text-2xl font-bold text-gray-900 mb-6">Full Transcript</h2>

          <div className="space-y-3">
            {report.transcript.map((item) => (
              <div
                key={item.questionNumber}
                className="border border-gray-200 rounded-lg overflow-hidden"
              >
                <button
                  onClick={() => toggleQuestion(item.questionNumber)}
                  className="w-full p-4 flex items-center justify-between hover:bg-gray-50 transition-colors"
                >
                  <div className="flex items-center gap-4 flex-1 text-left">
                    <div className="flex-shrink-0">
                      <div className="w-8 h-8 bg-blue-500 text-white rounded-full flex items-center justify-center text-sm font-semibold">
                        {item.questionNumber}
                      </div>
                    </div>
                    <div>
                      <p className="font-semibold text-gray-900">
                        {item.question}
                      </p>
                      <p className="text-sm text-gray-600">
                        {item.dimension} • Score:{' '}
                        <span className="font-semibold text-gray-900">
                          {Math.round(item.answerScore)}
                        </span>
                      </p>
                    </div>
                  </div>
                  {expandedQuestions.includes(item.questionNumber) ? (
                    <ChevronUp size={20} className="text-gray-400" />
                  ) : (
                    <ChevronDown size={20} className="text-gray-400" />
                  )}
                </button>

                {expandedQuestions.includes(item.questionNumber) && (
                  <div className="bg-gray-50 border-t border-gray-200 p-4 space-y-4">
                    <div>
                      <p className="text-sm font-semibold text-gray-700 mb-2">
                        Candidate Answer
                      </p>
                      <p className="text-gray-700 leading-relaxed">{item.answer}</p>
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-gray-700 mb-2">
                        Feedback
                      </p>
                      <p className="text-gray-700 leading-relaxed">{item.feedback}</p>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Video Recording */}
      {session.videoUrl && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
          <h2 className="text-2xl font-bold text-gray-900 mb-4">Video Recording</h2>
          {session.videoExpiresAt && (
            <div className="mb-4 p-3 bg-yellow-50 border border-yellow-200 rounded-lg flex gap-2">
              <Clock size={18} className="text-yellow-600 flex-shrink-0" />
              <p className="text-sm text-yellow-800">
                Video expires on{' '}
                {format(new Date(session.videoExpiresAt), 'MMM dd, yyyy')}
              </p>
            </div>
          )}
          <div className="aspect-video bg-gray-100 rounded-lg overflow-hidden flex items-center justify-center">
            <a
              href={session.videoUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-2 px-6 py-3 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
            >
              <Play size={20} />
              Play Video
            </a>
          </div>
        </div>
      )}

      {/* Anti-Cheat Flags */}
      {session.antiCheatFlags && session.antiCheatFlags.length > 0 && (
        <div className="bg-white rounded-lg shadow-sm border border-red-200 p-8">
          <h2 className="text-2xl font-bold text-red-900 mb-4">Anti-Cheat Alerts</h2>
          <div className="space-y-2">
            {session.antiCheatFlags.map((flag) => (
              <div
                key={flag.id}
                className={`p-4 rounded-lg ${
                  flag.severity === 'CRITICAL'
                    ? 'bg-red-50 border border-red-200'
                    : 'bg-yellow-50 border border-yellow-200'
                }`}
              >
                <div className="flex items-start gap-3">
                  <AlertCircle
                    size={20}
                    className={
                      flag.severity === 'CRITICAL'
                        ? 'text-red-600'
                        : 'text-yellow-600'
                    }
                  />
                  <div>
                    <p
                      className={`font-semibold ${
                        flag.severity === 'CRITICAL'
                          ? 'text-red-900'
                          : 'text-yellow-900'
                      }`}
                    >
                      {flag.type.replace(/_/g, ' ')}
                    </p>
                    <p
                      className={`text-sm ${
                        flag.severity === 'CRITICAL'
                          ? 'text-red-700'
                          : 'text-yellow-700'
                      }`}
                    >
                      {format(new Date(flag.timestamp), 'HH:mm:ss')}
                    </p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Notes */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-100 p-8">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-2xl font-bold text-gray-900">Employer Notes</h2>
          {!isEditingNotes && (
            <button
              onClick={() => setIsEditingNotes(true)}
              className="text-blue-500 hover:text-blue-600 font-medium text-sm"
            >
              Edit
            </button>
          )}
        </div>

        {isEditingNotes ? (
          <div className="space-y-4">
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={5}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="Add your notes about this candidate..."
            ></textarea>
            <div className="flex gap-4">
              <button
                onClick={handleSaveNotes}
                disabled={updateNotes.isPending}
                className="px-6 py-2 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-400 text-white rounded-lg font-medium transition-colors"
              >
                {updateNotes.isPending ? 'Saving...' : 'Save Notes'}
              </button>
              <button
                onClick={() => {
                  setIsEditingNotes(false);
                  setNotes(session.notes || '');
                }}
                className="px-6 py-2 bg-gray-200 hover:bg-gray-300 text-gray-900 rounded-lg font-medium transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <p className="text-gray-700 leading-relaxed">
            {notes || <span className="text-gray-400">No notes added yet</span>}
          </p>
        )}
      </div>
    </div>
  );
};
