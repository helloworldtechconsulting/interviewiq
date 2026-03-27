import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, AlertCircle, Calendar, Clock, CheckCircle } from 'lucide-react';
import { useSessions } from '../hooks/useSessions';
import { useJobs } from '../hooks/useJobs';
import { LoadingSpinner } from '../components/LoadingSpinner';
import { format, parse, addDays } from 'date-fns';

export const SchedulePage = () => {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const { bookSlot } = useSessions();

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState<'loading' | 'selecting' | 'confirming'>('loading');
  const [selectedSlotId, setSelectedSlotId] = useState<string | null>(null);

  const { getJobSlots } = useJobs();
  const { data: slots, isLoading: slotsLoading } = getJobSlots(jobId || '');

  // Parse token to get session and job IDs
  useEffect(() => {
    const verifyAndFetch = async () => {
      try {
        setLoading(true);

        // In a real scenario, token would be verified via API
        // For now, extract from token format
        const decoded = JSON.parse(atob(token || ''));
        setSessionId(decoded.sessionId);
        setJobId(decoded.jobId);

        setStage('selecting');
        setLoading(false);
      } catch (err) {
        setError('Invalid scheduling link');
        setLoading(false);
      }
    };

    verifyAndFetch();
  }, [token]);

  const handleSelectSlot = (slotId: string) => {
    setSelectedSlotId(slotId);
    setStage('confirming');
  };

  const handleConfirmBooking = async () => {
    if (!sessionId || !selectedSlotId) return;

    try {
      await bookSlot.mutateAsync({
        sessionId,
        slotId: selectedSlotId,
      });
      setStage('confirming'); // Show confirmation
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to book slot');
    }
  };

  if (loading || slotsLoading) return <LoadingSpinner />;

  if (error) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-navy via-navy-light to-blue-600 flex items-center justify-center p-4">
        <div className="w-full max-w-md bg-white rounded-lg shadow-xl p-8">
          <div className="flex gap-3 mb-4">
            <AlertCircle size={24} className="text-red-600 flex-shrink-0" />
            <div>
              <h1 className="text-2xl font-bold text-gray-900">Error</h1>
              <p className="text-gray-600 mt-1">{error}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const selectedSlot = slots?.find((s) => s.id === selectedSlotId);

  return (
    <div className="min-h-screen bg-gradient-to-br from-navy via-navy-light to-blue-600 flex items-center justify-center p-4">
      {/* Slot Selection */}
      {stage === 'selecting' && (
        <div className="w-full max-w-2xl">
          <button
            onClick={() => navigate('/')}
            className="flex items-center gap-2 text-white hover:text-gray-200 font-medium mb-6"
          >
            <ArrowLeft size={20} />
            Back
          </button>

          <div className="bg-white rounded-lg shadow-xl p-8">
            <h1 className="text-3xl font-bold text-gray-900 mb-2">
              Schedule Your Interview
            </h1>
            <p className="text-gray-600 mb-8">
              Select an available time slot to schedule your interview
            </p>

            {!slots || slots.length === 0 ? (
              <div className="text-center py-12">
                <Calendar size={48} className="mx-auto text-gray-300 mb-4" />
                <p className="text-gray-600 font-medium">No available slots</p>
                <p className="text-gray-500 text-sm mt-1">
                  Please contact the employer to schedule an interview
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {slots.map((slot) => (
                  <button
                    key={slot.id}
                    onClick={() => handleSelectSlot(slot.id)}
                    className="w-full p-4 border-2 border-gray-200 hover:border-blue-500 rounded-lg text-left transition-colors group"
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-4">
                        <div className="p-3 bg-blue-50 group-hover:bg-blue-100 rounded-lg">
                          <Calendar size={24} className="text-blue-500" />
                        </div>
                        <div>
                          <p className="font-semibold text-gray-900">
                            {format(new Date(slot.startTime), 'EEE, MMM dd')}
                          </p>
                          <div className="flex items-center gap-2 text-gray-600 text-sm mt-1">
                            <Clock size={16} />
                            <span>
                              {format(new Date(slot.startTime), 'HH:mm')} -{' '}
                              {format(new Date(slot.endTime), 'HH:mm')}
                            </span>
                          </div>
                        </div>
                      </div>
                      <div className="text-right">
                        <p className="text-sm font-medium text-gray-600">
                          {slot.maxInterviews - slot.bookedInterviews} of{' '}
                          {slot.maxInterviews} available
                        </p>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Confirmation */}
      {stage === 'confirming' && selectedSlot && (
        <div className="w-full max-w-md">
          <div className="bg-white rounded-lg shadow-xl p-8 text-center">
            <CheckCircle size={64} className="mx-auto text-green-500 mb-4" />
            <h1 className="text-2xl font-bold text-gray-900 mb-2">
              Interview Scheduled!
            </h1>
            <p className="text-gray-600 mb-8">
              Your interview has been successfully scheduled
            </p>

            <div className="bg-gray-50 rounded-lg p-6 mb-8 text-left">
              <div className="space-y-4">
                <div>
                  <p className="text-gray-600 text-sm">Date</p>
                  <p className="font-semibold text-gray-900">
                    {format(new Date(selectedSlot.startTime), 'EEEE, MMMM dd, yyyy')}
                  </p>
                </div>
                <div>
                  <p className="text-gray-600 text-sm">Time</p>
                  <p className="font-semibold text-gray-900">
                    {format(new Date(selectedSlot.startTime), 'HH:mm')} -{' '}
                    {format(new Date(selectedSlot.endTime), 'HH:mm')}
                  </p>
                </div>
              </div>
            </div>

            <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-8">
              <p className="text-blue-900 text-sm">
                You will receive a confirmation email shortly with a link to join the
                interview
              </p>
            </div>

            <button
              onClick={() => navigate('/')}
              className="w-full px-6 py-3 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-medium transition-colors"
            >
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
