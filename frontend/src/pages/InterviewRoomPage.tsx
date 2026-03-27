import { useState, useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import {
  Mic,
  MicOff,
  Camera,
  CameraOff,
  AlertCircle,
  Clock,
  CheckCircle,
  AlertTriangle,
} from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import { LoadingSpinner } from '../components/LoadingSpinner';

interface InterviewQuestion {
  type: 'QUESTION';
  questionNumber: number;
  totalQuestions: number;
  text: string;
  dimension: string;
}

interface InterviewComplete {
  type: 'INTERVIEW_COMPLETE';
  message: string;
}

type WebSocketMessage = InterviewQuestion | InterviewComplete | { type: 'ERROR'; message: string };

const ANTI_CHEAT_CHECK_INTERVAL = 5000; // 5 seconds

export const InterviewRoomPage = () => {
  const { token } = useParams<{ token: string }>();
  const { verifyCandidate } = useAuth();

  // State
  const [sessionData, setSessionData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState<
    'preview' | 'instructions' | 'active' | 'complete'
  >('preview');

  // Interview state
  const [currentQuestion, setCurrentQuestion] = useState<InterviewQuestion | null>(
    null
  );
  const [isRecording, setIsRecording] = useState(false);
  const [isMicOn, setIsMicOn] = useState(true);
  const [isCameraOn, setIsCameraOn] = useState(true);
  const [elapsedTime, setElapsedTime] = useState(0);
  const [answers, setAnswers] = useState<string[]>([]);
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [antiCheatWarnings, setAntiCheatWarnings] = useState<string[]>([]);

  // Refs
  const wsRef = useRef<WebSocket | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);
  const recognitionRef = useRef<any>(null);
  const timerIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const antiCheatIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const faceDetectionCanvasRef = useRef<HTMLCanvasElement | null>(null);

  // Initialize audio/video on mount
  useEffect(() => {
    const initializeMedia = async () => {
      try {
        setLoading(true);

        // Verify candidate
        if (!token) {
          setError('Invalid interview token');
          setLoading(false);
          return;
        }

        const verifyResult = await verifyCandidate.mutateAsync({ token });
        if (!verifyResult.data.success) {
          setError('Failed to verify candidate');
          setLoading(false);
          return;
        }

        setSessionData(verifyResult.data.data);

        // Request media access
        const stream = await navigator.mediaDevices.getUserMedia({
          video: true,
          audio: true,
        });

        mediaStreamRef.current = stream;
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
        }

        // Setup speech recognition
        const SpeechRecognition =
          window.webkitSpeechRecognition || window.SpeechRecognition;
        if (SpeechRecognition) {
          const recognition = new SpeechRecognition();
          recognition.continuous = true;
          recognition.interimResults = true;
          recognitionRef.current = recognition;
        }

        setStage('instructions');
        setLoading(false);
      } catch (err) {
        console.error('Media initialization error:', err);
        setError('Unable to access camera/microphone. Please check permissions.');
        setLoading(false);
      }
    };

    initializeMedia();

    return () => {
      if (mediaStreamRef.current) {
        mediaStreamRef.current.getTracks().forEach((track) => track.stop());
      }
    };
  }, [token, verifyCandidate]);

  // Anti-cheat monitoring
  useEffect(() => {
    if (stage !== 'active') return;

    antiCheatIntervalRef.current = setInterval(() => {
      // Check tab visibility
      if (document.hidden) {
        addAntiCheatWarning('Tab switch detected');
      }

      // Check camera
      if (mediaStreamRef.current) {
        const videoTracks = mediaStreamRef.current.getVideoTracks();
        if (videoTracks.length > 0 && !videoTracks[0].enabled) {
          addAntiCheatWarning('Camera turned off');
        }
      }

      // Simple face detection check
      if (videoRef.current && faceDetectionCanvasRef.current) {
        checkFacePresence();
      }
    }, ANTI_CHEAT_CHECK_INTERVAL);

    return () => {
      if (antiCheatIntervalRef.current) {
        clearInterval(antiCheatIntervalRef.current);
      }
    };
  }, [stage]);

  const addAntiCheatWarning = (message: string) => {
    setAntiCheatWarnings((prev) => {
      const updated = [...prev, message];
      return updated.slice(-5); // Keep last 5 warnings
    });
  };

  const checkFacePresence = () => {
    // Simple check: analyze canvas pixel brightness
    // In production, use a proper face detection library like face-api.js
    if (!videoRef.current || !faceDetectionCanvasRef.current) return;

    const ctx = faceDetectionCanvasRef.current.getContext('2d');
    if (!ctx) return;

    ctx.drawImage(videoRef.current, 0, 0, 80, 60);
    const imageData = ctx.getImageData(0, 0, 80, 60);
    const data = imageData.data;

    let brightness = 0;
    for (let i = 0; i < data.length; i += 4) {
      brightness +=
        (data[i] + data[i + 1] + data[i + 2]) / 3 / (data.length / 4);
    }

    // If brightness too low, might indicate face not present
    if (brightness < 20) {
      addAntiCheatWarning('Face not clearly detected');
    }
  };

  const startInterview = async () => {
    if (!sessionData) return;

    try {
      setStage('active');
      setIsRecording(true);
      recordedChunksRef.current = [];

      // Start media recorder
      if (mediaStreamRef.current) {
        const mediaRecorder = new MediaRecorder(mediaStreamRef.current);
        mediaRecorder.ondataavailable = (event) => {
          if (event.data.size > 0) {
            recordedChunksRef.current.push(event.data);
          }
        };
        mediaRecorder.start();
        mediaRecorderRef.current = mediaRecorder;
      }

      // Start timer
      timerIntervalRef.current = setInterval(() => {
        setElapsedTime((prev) => prev + 1);
      }, 1000);

      // Connect WebSocket
      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const wsUrl = `${protocol}://${window.location.host}/ws/interview/${sessionData.sessionId}`;

      wsRef.current = new WebSocket(wsUrl);

      wsRef.current.onopen = () => {
        // Send access token for authentication
        wsRef.current?.send(JSON.stringify({ type: 'AUTH', token: sessionData.accessToken }));
      };

      wsRef.current.onmessage = (event) => {
        const message: WebSocketMessage = JSON.parse(event.data);

        if (message.type === 'QUESTION') {
          const q = message as InterviewQuestion;
          setCurrentQuestion(q);
          speakQuestion(q.text);
        } else if (message.type === 'INTERVIEW_COMPLETE') {
          endInterview();
        } else if (message.type === 'ERROR') {
          setError((message as any).message || 'Interview error');
        }
      };

      wsRef.current.onerror = () => {
        setError('Connection error. Please check your internet.');
      };

      wsRef.current.onclose = () => {
        if (stage === 'active') {
          setError('Connection lost');
        }
      };
    } catch (err) {
      setError('Failed to start interview');
    }
  };

  const speakQuestion = (text: string) => {
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 1;
    window.speechSynthesis.speak(utterance);
  };

  const startAnswerCapture = () => {
    if (!recognitionRef.current || !currentQuestion) return;

    setIsTranscribing(true);
    let finalTranscript = '';

    recognitionRef.current.onresult = (event: any) => {
      let interimTranscript = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          finalTranscript += transcript + ' ';
        } else {
          interimTranscript += transcript;
        }
      }
    };

    recognitionRef.current.onend = () => {
      setIsTranscribing(false);
      if (finalTranscript.trim()) {
        submitAnswer(finalTranscript.trim());
      }
    };

    recognitionRef.current.start();
  };

  const submitAnswer = (text: string) => {
    if (!wsRef.current || !currentQuestion) return;

    const newAnswers = [...answers, text];
    setAnswers(newAnswers);

    wsRef.current.send(
      JSON.stringify({
        type: 'ANSWER',
        questionNumber: currentQuestion.questionNumber,
        text: text,
      })
    );

    setCurrentQuestion(null);
  };

  const endInterview = async () => {
    setStage('complete');
    setIsRecording(false);

    // Stop all timers
    if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    if (antiCheatIntervalRef.current) clearInterval(antiCheatIntervalRef.current);

    // Stop media recorder and upload
    if (mediaRecorderRef.current) {
      mediaRecorderRef.current.stop();

      setTimeout(async () => {
        const videoBlob = new Blob(recordedChunksRef.current, {
          type: 'video/webm',
        });

        // Upload video to backend
        const formData = new FormData();
        formData.append('video', videoBlob);
        formData.append('sessionId', sessionData?.sessionId);

        try {
          await fetch('/api/v1/sessions/upload-video', {
            method: 'POST',
            body: formData,
            headers: {
              Authorization: `Bearer ${sessionData?.accessToken}`,
            },
          });
        } catch (err) {
          console.error('Video upload error:', err);
        }
      }, 100);
    }

    // Close WebSocket
    if (wsRef.current) {
      wsRef.current.close();
    }

    // Stop speech synthesis
    window.speechSynthesis.cancel();

    // Stop media tracks
    if (mediaStreamRef.current) {
      mediaStreamRef.current.getTracks().forEach((track) => track.stop());
    }
  };

  if (loading) return <LoadingSpinner />;

  const formatTime = (seconds: number) => {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div className="min-h-screen bg-navy text-white flex items-center justify-center p-4">
      <canvas
        ref={faceDetectionCanvasRef}
        width={80}
        height={60}
        style={{ display: 'none' }}
      ></canvas>

      {error && (
        <div className="fixed top-4 right-4 max-w-md bg-red-500 text-white p-4 rounded-lg shadow-lg flex gap-3">
          <AlertCircle size={20} className="flex-shrink-0 mt-0.5" />
          <div>
            <p className="font-semibold">Error</p>
            <p className="text-sm">{error}</p>
          </div>
        </div>
      )}

      {/* Preview Stage */}
      {stage === 'preview' && (
        <div className="w-full max-w-2xl">
          <div className="bg-white rounded-lg p-8 text-gray-900">
            <h1 className="text-3xl font-bold mb-2">Welcome to InterviewIQ</h1>
            <p className="text-gray-600 mb-8">
              Please enable camera and microphone to continue
            </p>

            <div className="aspect-video bg-gray-200 rounded-lg overflow-hidden mb-8 flex items-center justify-center">
              <video
                ref={videoRef}
                autoPlay
                playsInline
                className="w-full h-full object-cover"
              />
            </div>

            <div className="space-y-2 mb-8">
              <div className="flex items-center gap-2">
                {isCameraOn ? (
                  <Camera size={20} className="text-green-500" />
                ) : (
                  <CameraOff size={20} className="text-red-500" />
                )}
                <span>Camera: {isCameraOn ? 'On' : 'Off'}</span>
              </div>
              <div className="flex items-center gap-2">
                {isMicOn ? (
                  <Mic size={20} className="text-green-500" />
                ) : (
                  <MicOff size={20} className="text-red-500" />
                )}
                <span>Microphone: {isMicOn ? 'On' : 'Off'}</span>
              </div>
            </div>

            <button
              onClick={startInterview}
              disabled={!isCameraOn || !isMicOn}
              className="w-full px-6 py-3 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-400 text-white rounded-lg font-semibold transition-colors"
            >
              Start Interview
            </button>
          </div>
        </div>
      )}

      {/* Instructions Stage */}
      {stage === 'instructions' && sessionData && (
        <div className="w-full max-w-2xl">
          <div className="bg-white rounded-lg p-8 text-gray-900 mb-6">
            <h1 className="text-3xl font-bold mb-2">{sessionData.jobTitle}</h1>
            <p className="text-gray-600 mb-6">{sessionData.companyName}</p>

            <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-8">
              <h2 className="font-semibold text-blue-900 mb-2">Instructions</h2>
              <p className="text-blue-800 text-sm leading-relaxed">
                {sessionData.instructions ||
                  'You will be asked 10 interview questions. Please answer each question clearly and thoroughly. The interview will take approximately 30 minutes. Your answers will be recorded and evaluated by our AI system.'}
              </p>
            </div>

            <div className="grid grid-cols-2 gap-4 mb-8">
              <div className="bg-gray-50 p-4 rounded-lg">
                <p className="text-sm text-gray-600">Total Questions</p>
                <p className="text-2xl font-bold text-gray-900">
                  {sessionData.totalQuestions}
                </p>
              </div>
              <div className="bg-gray-50 p-4 rounded-lg">
                <p className="text-sm text-gray-600">Max Duration</p>
                <p className="text-2xl font-bold text-gray-900">
                  {sessionData.maxDurationMinutes} min
                </p>
              </div>
            </div>

            <button
              onClick={startInterview}
              className="w-full px-6 py-3 bg-blue-500 hover:bg-blue-600 text-white rounded-lg font-semibold transition-colors"
            >
              Start Interview
            </button>
          </div>
        </div>
      )}

      {/* Active Interview Stage */}
      {stage === 'active' && currentQuestion && (
        <div className="w-full max-w-6xl grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Video Feed */}
          <div className="lg:col-span-1">
            <div className="bg-black rounded-lg overflow-hidden aspect-video flex items-center justify-center mb-4">
              <video
                ref={videoRef}
                autoPlay
                playsInline
                className="w-full h-full object-cover"
              />
            </div>

            {/* Anti-Cheat Warnings */}
            {antiCheatWarnings.length > 0 && (
              <div className="bg-yellow-900 rounded-lg p-4 space-y-2">
                {antiCheatWarnings.map((warning, idx) => (
                  <div key={idx} className="flex items-start gap-2">
                    <AlertTriangle size={18} className="flex-shrink-0 mt-0.5" />
                    <span className="text-sm">{warning}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Question & Answer */}
          <div className="lg:col-span-2 space-y-6">
            {/* Question Card */}
            <div className="bg-white text-gray-900 rounded-lg p-6">
              <div className="flex items-center justify-between mb-4">
                <div>
                  <p className="text-sm font-medium text-gray-600">
                    Question {currentQuestion.questionNumber} of{' '}
                    {currentQuestion.totalQuestions}
                  </p>
                  <p className="text-xs text-gray-500 mt-1">
                    {currentQuestion.dimension}
                  </p>
                </div>
                <div className="text-2xl font-bold text-gray-900">
                  {formatTime(elapsedTime)}
                </div>
              </div>

              <h2 className="text-2xl font-bold text-gray-900 mb-6">
                {currentQuestion.text}
              </h2>

              {/* Recording Controls */}
              <div className="flex gap-4">
                <button
                  onClick={() => setIsMicOn(!isMicOn)}
                  className={`flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg font-medium transition-colors ${
                    isMicOn
                      ? 'bg-blue-500 hover:bg-blue-600 text-white'
                      : 'bg-red-500 hover:bg-red-600 text-white'
                  }`}
                >
                  {isMicOn ? (
                    <>
                      <Mic size={20} />
                      Microphone On
                    </>
                  ) : (
                    <>
                      <MicOff size={20} />
                      Microphone Off
                    </>
                  )}
                </button>

                <button
                  onClick={() => setIsCameraOn(!isCameraOn)}
                  className={`flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg font-medium transition-colors ${
                    isCameraOn
                      ? 'bg-blue-500 hover:bg-blue-600 text-white'
                      : 'bg-red-500 hover:bg-red-600 text-white'
                  }`}
                >
                  {isCameraOn ? (
                    <>
                      <Camera size={20} />
                      Camera On
                    </>
                  ) : (
                    <>
                      <CameraOff size={20} />
                      Camera Off
                    </>
                  )}
                </button>
              </div>
            </div>

            {/* Answer Recording */}
            <div className="bg-white text-gray-900 rounded-lg p-6">
              <p className="text-sm font-medium text-gray-600 mb-4">
                Your Answer
              </p>
              {isTranscribing ? (
                <div className="space-y-4">
                  <div className="flex items-center justify-center gap-2 py-6">
                    <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse"></div>
                    <p className="text-gray-700 font-medium">Listening...</p>
                  </div>
                  <button
                    onClick={() => {
                      recognitionRef.current?.stop();
                      setIsTranscribing(false);
                    }}
                    className="w-full px-4 py-3 bg-red-500 hover:bg-red-600 text-white rounded-lg font-medium transition-colors"
                  >
                    Stop Recording
                  </button>
                </div>
              ) : (
                <button
                  onClick={startAnswerCapture}
                  className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-green-500 hover:bg-green-600 text-white rounded-lg font-medium transition-colors"
                >
                  <Mic size={20} />
                  Record Answer
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Complete Stage */}
      {stage === 'complete' && (
        <div className="w-full max-w-2xl">
          <div className="bg-white rounded-lg p-8 text-gray-900 text-center">
            <CheckCircle size={64} className="mx-auto text-green-500 mb-4" />
            <h1 className="text-3xl font-bold mb-2">Interview Complete!</h1>
            <p className="text-gray-600 mb-8">
              Thank you for completing the interview. Your responses have been recorded
              and will be evaluated by our AI system.
            </p>

            <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-8">
              <p className="text-blue-900">
                You will receive an email with your results shortly.
              </p>
            </div>

            <p className="text-gray-600">
              Total duration: {formatTime(elapsedTime)}
            </p>
          </div>
        </div>
      )}
    </div>
  );
};
