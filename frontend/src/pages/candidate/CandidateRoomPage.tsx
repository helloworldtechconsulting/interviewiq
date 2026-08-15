// =============================================================================
//
//
//
//
// =============================================================================

import {
  useCallback,
  useEffect,
  useReducer,
  useRef,
  useState,
} from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery, useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  Video,
  VideoOff,
  Mic,
  MicOff,
  CheckCircle2,
  Clock,
  AlertTriangle,
  ChevronRight,
  Loader2,
  Volume2,
  SkipForward,
} from "lucide-react";

import {
  candidateRoomApi,
  type QuestionAnswer,
  type ProctoringFlagPayload,
} from "@/api/modules/candidate";
import { GoogleSignInButton } from "@/components/auth/GoogleSignInButton";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { InterviewQuestion } from "@/types";

function checkBrowserSupport(): string | null {
  if (!navigator.mediaDevices?.getUserMedia) return "Camera/microphone access is not supported in this browser.";
  if (!window.speechSynthesis) return "Text-to-speech is not supported in this browser.";
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return "Speech recognition is not supported. Please use Chrome or Edge.";
  if (!window.MediaRecorder) return "Video recording is not supported in this browser.";
  return null; // all good
}

type RoomPhase =
  | "CHECKING"      // initial load: checking browser support + fetching init data
  | "INCOMPATIBLE"  // browser doesn't support required APIs
  | "WAITING"       // questions not ready yet (still generating) or interview hasn't started
  | "GOOGLE_AUTH"   // candidate must verify Google identity before setup
  | "SETUP"         // questions loaded, asking for camera/mic permission
  | "READY"         // camera/mic granted, camera preview shown, ready to begin
  | "SPEAKING"      // TTS is reading the current question aloud
  | "LISTENING"     // STT is recording the candidate's answer
  | "REVIEWING"     // candidate reviews/edits transcript before moving on
  | "UPLOADING"     // all questions done, uploading video recording to S3
  | "SUBMITTED"     // upload complete, evaluation in progress
  | "COMPLETED"     // evaluation done or session already completed
  | "CANCELLED"
  | "FAILED"
  | "INVALID_TOKEN";

interface ProctoringAccumulator {
  tabSwitchCount: number;
  firstTabSwitch: string | null;
  cameraLostCount: number;
  firstCameraLost: string | null;
}

interface RoomState {
  phase: RoomPhase;
  currentQuestionIndex: number;
  answers: QuestionAnswer[];           // accumulated per-question transcripts
  proctoring: ProctoringAccumulator;
  incompatibleReason: string | null;
  currentTranscript: string;           // live STT text for current question
  isSpeaking: boolean;                 // TTS in progress
  isListening: boolean;                // STT in progress
  cameraActive: boolean;
}

type RoomAction =
  | { type: "SET_PHASE"; phase: RoomPhase }
  | { type: "INCOMPATIBLE"; reason: string }
  | { type: "NEXT_QUESTION"; transcript: string }
  | { type: "UPDATE_TRANSCRIPT"; text: string }
  | { type: "SET_SPEAKING"; value: boolean }
  | { type: "SET_LISTENING"; value: boolean }
  | { type: "SET_CAMERA"; active: boolean }
  | { type: "TAB_SWITCH" }
  | { type: "CAMERA_LOST" };

function reducer(state: RoomState, action: RoomAction): RoomState {
  switch (action.type) {
    case "SET_PHASE":
      return { ...state, phase: action.phase };
    case "INCOMPATIBLE":
      return { ...state, phase: "INCOMPATIBLE", incompatibleReason: action.reason };
    case "NEXT_QUESTION": {
      const saved: QuestionAnswer = {
        questionOrder: state.currentQuestionIndex + 1,
        transcript: action.transcript,
      };
      return {
        ...state,
        answers: [...state.answers, saved],
        currentQuestionIndex: state.currentQuestionIndex + 1,
        currentTranscript: "",
        isSpeaking: false,
        isListening: false,
      };
    }
    case "UPDATE_TRANSCRIPT":
      return { ...state, currentTranscript: action.text };
    case "SET_SPEAKING":
      return { ...state, isSpeaking: action.value };
    case "SET_LISTENING":
      return { ...state, isListening: action.value };
    case "SET_CAMERA":
      return { ...state, cameraActive: action.active };
    case "TAB_SWITCH":
      return {
        ...state,
        proctoring: {
          ...state.proctoring,
          tabSwitchCount: state.proctoring.tabSwitchCount + 1,
          firstTabSwitch: state.proctoring.firstTabSwitch ?? new Date().toISOString(),
        },
      };
    case "CAMERA_LOST":
      return {
        ...state,
        proctoring: {
          ...state.proctoring,
          cameraLostCount: state.proctoring.cameraLostCount + 1,
          firstCameraLost: state.proctoring.firstCameraLost ?? new Date().toISOString(),
        },
      };
    default:
      return state;
  }
}

const initialState: RoomState = {
  phase: "CHECKING",
  currentQuestionIndex: 0,
  answers: [],
  proctoring: {
    tabSwitchCount: 0,
    firstTabSwitch: null,
    cameraLostCount: 0,
    firstCameraLost: null,
  },
  incompatibleReason: null,
  currentTranscript: "",
  isSpeaking: false,
  isListening: false,
  cameraActive: false,
};

function useCountdown(targetIso: string | null | undefined) {
  const [diff, setDiff] = useState(0);
  useEffect(() => {
    if (!targetIso) return;
    const target = new Date(targetIso).getTime();
    const tick = () => setDiff(Math.max(0, target - Date.now()));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [targetIso]);
  const s = Math.floor(diff / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    display: h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`,
    isPast: diff === 0,
  };
}

function ProgressBar({ current, total }: { current: number; total: number }) {
  const pct = total > 0 ? Math.round((current / total) * 100) : 0;
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>Question {current} of {total}</span>
        <span>{pct}%</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className="h-full bg-primary transition-all duration-500"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function ErrorScreen({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-destructive/10 p-6">
        <AlertTriangle className="h-12 w-12 text-destructive" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">{title}</h2>
        <p className="mt-2 text-muted-foreground max-w-md">{description}</p>
      </div>
    </div>
  );
}

function WaitingScreen({ scheduledAt }: { scheduledAt: string | null }) {
  const countdown = useCountdown(scheduledAt);
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-primary/10 p-6">
        <Clock className="h-12 w-12 text-primary" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">Preparing your interview…</h2>
        <p className="mt-2 text-muted-foreground">
          {scheduledAt && !countdown.isPast
            ? `Starts in ${countdown.display}`
            : "The AI is generating your personalised questions. This takes about 30 seconds."}
        </p>
      </div>
      <Loader2 className="h-8 w-8 animate-spin text-primary" />
    </div>
  );
}

function SubmittedScreen() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-primary/10 p-6">
        <Loader2 className="h-12 w-12 animate-spin text-primary" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">Interview submitted!</h2>
        <p className="mt-2 text-muted-foreground">
          The AI is evaluating your responses. You'll receive a notification when the
          results are ready. You can safely close this tab.
        </p>
      </div>
    </div>
  );
}

function CompletedScreen() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="rounded-full bg-green-100 p-6">
        <CheckCircle2 className="h-12 w-12 text-green-600" />
      </div>
      <div>
        <h2 className="text-2xl font-bold">Interview complete!</h2>
        <p className="mt-2 text-muted-foreground">
          Thank you for completing the interview. The hiring team will review your
          responses and be in touch soon. You can safely close this tab.
        </p>
      </div>
    </div>
  );
}

function CameraPreview({
  stream,
  active,
}: {
  stream: MediaStream | null;
  active: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    if (videoRef.current && stream) {
      videoRef.current.srcObject = stream;
    }
  }, [stream]);

  return (
    <div className="relative aspect-video w-full max-w-xs overflow-hidden rounded-xl bg-muted">
      {stream ? (
        <video
          ref={videoRef}
          autoPlay
          muted
          playsInline
          className="h-full w-full object-cover scale-x-[-1]"
        />
      ) : (
        <div className="flex h-full items-center justify-center">
          <VideoOff className="h-8 w-8 text-muted-foreground" />
        </div>
      )}
      <div
        className={cn(
          "absolute bottom-2 left-2 flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
          active ? "bg-green-500 text-white" : "bg-red-500 text-white",
        )}
      >
        {active ? <Video className="h-3 w-3" /> : <VideoOff className="h-3 w-3" />}
        {active ? "Camera on" : "Camera off"}
      </div>
    </div>
  );
}

export function CandidateRoomPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token");

  const [state, dispatch] = useReducer(reducer, {
    ...initialState,
    phase: token ? "CHECKING" : "INVALID_TOKEN",
  });

  const streamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const synthRef = useRef(window.speechSynthesis);

  const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
  const [recordingKey, setRecordingKey] = useState<string | null>(null);
  const [recordingUploadUrl, setRecordingUploadUrl] = useState<string | null>(null);

  const { data: initData, refetch: refetchInit } = useQuery({
    queryKey: ["candidate", "interview", "init"],
    queryFn: candidateRoomApi.initInterview,
    enabled: !!token && state.phase === "CHECKING",
    refetchInterval: false,
  });

  const startMutation = useMutation({
    mutationFn: candidateRoomApi.startInterview,
    onSuccess: () => {
      dispatch({ type: "SET_PHASE", phase: "SPEAKING" });
      speakQuestion(0);
    },
    onError: () => {
      toast.error("Failed to start interview. Please try again.");
    },
  });

  const completeMutation = useMutation({
    mutationFn: candidateRoomApi.completeInterview,
    onSuccess: () => dispatch({ type: "SET_PHASE", phase: "SUBMITTED" }),
    onError: () => {
      toast.error("Failed to submit interview. Please try again.");
      dispatch({ type: "SET_PHASE", phase: "FAILED" });
    },
  });

  const googleVerifyMutation = useMutation({
    mutationFn: (idToken: string) => candidateRoomApi.googleVerify(idToken),
    onSuccess: () => {
      dispatch({ type: "SET_PHASE", phase: "SETUP" });
    },
    onError: () => {
      toast.error("Google verification failed. Please try again.");
    },
  });

  useEffect(() => {
    if (!token) return;

    const incompatible = checkBrowserSupport();
    if (incompatible) {
      dispatch({ type: "INCOMPATIBLE", reason: incompatible });
      return;
    }

    if (!initData) return;

    if (initData.status === "COMPLETED") {
      dispatch({ type: "SET_PHASE", phase: "COMPLETED" });
      return;
    }
    if (initData.status === "CANCELLED") {
      dispatch({ type: "SET_PHASE", phase: "CANCELLED" });
      return;
    }
    if (initData.status === "ERROR" || initData.status === "EXPIRED") {
      dispatch({ type: "SET_PHASE", phase: "FAILED" });
      return;
    }

    if (
      initData.questionGenerationStatus !== "DONE" ||
      !initData.questionsJson
    ) {
      dispatch({ type: "SET_PHASE", phase: "WAITING" });
      const timer = setTimeout(() => refetchInit(), 5000);
      return () => clearTimeout(timer);
    }

    try {
      const parsed: InterviewQuestion[] = JSON.parse(initData.questionsJson);
      parsed.sort((a, b) => a.order - b.order);
      setQuestions(parsed);
      setRecordingKey(initData.recordingS3Key);
      setRecordingUploadUrl(initData.recordingUploadUrl);
    } catch {
      dispatch({ type: "SET_PHASE", phase: "FAILED" });
      return;
    }

    if (initData.status === "IN_PROGRESS") {
      requestCameraAndMic().then((ok) => {
        if (ok) {
          dispatch({ type: "SET_PHASE", phase: "SPEAKING" });
          speakQuestion(state.currentQuestionIndex);
        }
      });
    } else {
      if (initData.googleVerified === false) {
        dispatch({ type: "SET_PHASE", phase: "GOOGLE_AUTH" });
      } else {
        dispatch({ type: "SET_PHASE", phase: "SETUP" });
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initData, token]);

  useEffect(() => {
    if (!["SPEAKING", "LISTENING", "REVIEWING"].includes(state.phase)) return;

    const handleVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        dispatch({ type: "TAB_SWITCH" });
        toast.warning("Tab switch detected.", { description: "Please stay in this tab during the interview." });
      }
    };
    const handleBlur = () => {
      dispatch({ type: "TAB_SWITCH" });
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("blur", handleBlur);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      window.removeEventListener("blur", handleBlur);
    };
  }, [state.phase]);

  const requestCameraAndMic = useCallback(async (): Promise<boolean> => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: true,
        audio: true,
      });
      streamRef.current = stream;
      dispatch({ type: "SET_CAMERA", active: true });

      const recorder = new MediaRecorder(stream, {
        mimeType: MediaRecorder.isTypeSupported("video/webm;codecs=vp9,opus")
          ? "video/webm;codecs=vp9,opus"
          : "video/webm",
      });
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      recorder.start(5000); // collect chunks every 5s
      mediaRecorderRef.current = recorder;

      stream.getVideoTracks().forEach((track) => {
        track.onended = () => {
          dispatch({ type: "SET_CAMERA", active: false });
          dispatch({ type: "CAMERA_LOST" });
          toast.warning("Camera turned off", {
            description: "Please re-enable your camera to continue.",
          });
        };
      });

      return true;
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Unknown error";
      toast.error("Camera/microphone access denied", {
        description: msg,
      });
      return false;
    }
  }, []);

  const handleGrantPermission = useCallback(async () => {
    const ok = await requestCameraAndMic();
    if (ok) {
      dispatch({ type: "SET_PHASE", phase: "READY" });
    }
  }, [requestCameraAndMic]);

  const speakQuestion = useCallback((index: number) => {
    if (index >= questions.length) return;
    const synth = synthRef.current;
    synth.cancel();

    const utterance = new SpeechSynthesisUtterance(questions[index].text);
    utterance.rate = 0.9;
    utterance.pitch = 1.0;
    utterance.lang = "en-US";

    dispatch({ type: "SET_SPEAKING", value: true });

    utterance.onend = () => {
      dispatch({ type: "SET_SPEAKING", value: false });
      startListening();
    };

    utterance.onerror = () => {
      dispatch({ type: "SET_SPEAKING", value: false });
      startListening();
    };

    synth.speak(utterance);
  }, [questions]); // eslint-disable-line react-hooks/exhaustive-deps

  const startListening = useCallback(() => {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;

    recognitionRef.current?.abort();
    const recognition = new SR();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = "en-US";

    let finalTranscript = "";

    recognition.onresult = (event) => {
      let interim = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        if (result.isFinal) {
          finalTranscript += result[0].transcript + " ";
        } else {
          interim = result[0].transcript;
        }
      }
      dispatch({ type: "UPDATE_TRANSCRIPT", text: finalTranscript + interim });
    };

    recognition.onstart = () => {
      dispatch({ type: "SET_LISTENING", value: true });
      dispatch({ type: "SET_PHASE", phase: "LISTENING" });
    };

    recognition.onend = () => {
      dispatch({ type: "SET_LISTENING", value: false });
      dispatch({ type: "SET_PHASE", phase: "REVIEWING" });
    };

    recognition.onerror = (event) => {
      if (event.error !== "no-speech" && event.error !== "aborted") {
        toast.error(`Speech recognition error: ${event.error}`);
      }
      dispatch({ type: "SET_LISTENING", value: false });
      dispatch({ type: "SET_PHASE", phase: "REVIEWING" });
    };

    recognitionRef.current = recognition;
    recognition.start();
  }, []);

  const stopListening = useCallback(() => {
    recognitionRef.current?.stop();
  }, []);

  const handleNextQuestion = useCallback(async () => {
    recognitionRef.current?.abort();
    synthRef.current.cancel();

    const currentTranscript = state.currentTranscript;
    const nextIndex = state.currentQuestionIndex + 1;

    dispatch({ type: "NEXT_QUESTION", transcript: currentTranscript });

    if (nextIndex >= questions.length) {
      await handleFinishInterview([
        ...state.answers,
        { questionOrder: state.currentQuestionIndex + 1, transcript: currentTranscript },
      ]);
    } else {
      dispatch({ type: "SET_PHASE", phase: "SPEAKING" });
      speakQuestion(nextIndex);
    }
  }, [state.currentTranscript, state.currentQuestionIndex, state.answers, questions.length, speakQuestion]); // eslint-disable-line

  const handleFinishInterview = useCallback(
    async (finalAnswers: QuestionAnswer[]) => {
      dispatch({ type: "SET_PHASE", phase: "UPLOADING" });

      let s3KeyToSubmit: string | null = null;

      if (mediaRecorderRef.current?.state !== "inactive") {
        mediaRecorderRef.current?.stop();
        await new Promise<void>((resolve) => {
          if (!mediaRecorderRef.current) { resolve(); return; }
          mediaRecorderRef.current.onstop = () => resolve();
          setTimeout(resolve, 3000); // safety timeout
        });
      }

      if (chunksRef.current.length > 0 && recordingUploadUrl) {
        const blob = new Blob(chunksRef.current, { type: "video/webm" });
        const uploaded = await candidateRoomApi.uploadRecording(recordingUploadUrl, blob);
        if (uploaded) {
          s3KeyToSubmit = recordingKey;
        } else {
          toast.warning("Video upload failed", {
            description: "Your interview will still be evaluated from the transcript.",
          });
        }
      }

      streamRef.current?.getTracks().forEach((t) => t.stop());

      const proctoringFlags: ProctoringFlagPayload[] = [];
      const { tabSwitchCount, firstTabSwitch, cameraLostCount, firstCameraLost } =
        state.proctoring;

      if (tabSwitchCount > 0) {
        proctoringFlags.push({
          type: "TAB_SWITCH",
          count: tabSwitchCount,
          firstOccurrence: firstTabSwitch ?? new Date().toISOString(),
        });
      }
      if (cameraLostCount > 0) {
        proctoringFlags.push({
          type: "CAMERA_LOST",
          count: cameraLostCount,
          firstOccurrence: firstCameraLost ?? new Date().toISOString(),
        });
      }

      completeMutation.mutate({
        answers: finalAnswers,
        proctoringFlags,
        recordingS3Key: s3KeyToSubmit,
      });
    },
    [recordingUploadUrl, recordingKey, state.proctoring, completeMutation],
  );

  const handleBeginInterview = useCallback(() => {
    startMutation.mutate();
  }, [startMutation]);

  if (state.phase === "INVALID_TOKEN") {
    return (
      <ErrorScreen
        title="Invalid interview link"
        description="This link is missing your invite token. Please use the original link from your invitation email."
      />
    );
  }

  if (state.phase === "INCOMPATIBLE") {
    return (
      <ErrorScreen
        title="Browser not supported"
        description={
          state.incompatibleReason ??
          "Please use the latest Chrome or Edge to complete this interview."
        }
      />
    );
  }

  if (state.phase === "CHECKING") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4">
        <Loader2 className="h-10 w-10 animate-spin text-primary" />
        <p className="text-muted-foreground">Loading your interview…</p>
      </div>
    );
  }

  if (state.phase === "WAITING") {
    return (
      <WaitingScreen
        scheduledAt={initData?.scheduledAt ?? null}
      />
    );
  }

  if (state.phase === "CANCELLED") {
    return (
      <ErrorScreen
        title="Interview cancelled"
        description="This session was cancelled by the hiring team. Please contact them if you believe this is a mistake."
      />
    );
  }

  if (state.phase === "FAILED") {
    return (
      <ErrorScreen
        title="Interview could not be completed"
        description="There was a technical issue. Please contact the hiring team to reschedule."
      />
    );
  }

  if (state.phase === "SUBMITTED") {
    return <SubmittedScreen />;
  }

  if (state.phase === "COMPLETED") {
    return <CompletedScreen />;
  }

  if (state.phase === "GOOGLE_AUTH") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-8 p-8 text-center">
        <div>
          <h2 className="text-2xl font-bold">Verify your identity</h2>
          <p className="mt-2 text-muted-foreground max-w-md">
            This interview requires you to verify your identity with your Google account
            before proceeding. This ensures the results are attributed to you.
          </p>
        </div>

        <div className="w-full max-w-xs">
          <GoogleSignInButton
            text="continue_with"
            onSuccess={(idToken) => googleVerifyMutation.mutate(idToken)}
            onError={() => toast.error("Google sign-in failed. Please try again.")}
            disabled={googleVerifyMutation.isPending}
          />
        </div>

        {googleVerifyMutation.isPending && (
          <div className="flex items-center gap-2 text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span className="text-sm">Verifying…</span>
          </div>
        )}
      </div>
    );
  }

  if (state.phase === "SETUP") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-8 p-8 text-center">
        <div>
          <h2 className="text-2xl font-bold">Ready to begin</h2>
          <p className="mt-2 text-muted-foreground max-w-md">
            This is an AI-powered video interview. Your camera and microphone are required.
            The AI will read each question aloud, then listen to your spoken answer.
          </p>
        </div>

        <div className="flex max-w-xl flex-col gap-3 rounded-lg border p-5 text-left text-sm">
          <p className="font-semibold text-foreground">Before you begin:</p>
          <ul className="space-y-1.5 text-muted-foreground list-disc list-inside">
            <li>Find a quiet location with good lighting</li>
            <li>Allow camera and microphone access when prompted</li>
            <li>Speak clearly and at a normal pace</li>
            <li>Stay in this tab throughout the interview — tab switches are recorded</li>
            <li>You can also type/edit your answers before moving to the next question</li>
          </ul>
        </div>

        <Button size="lg" onClick={handleGrantPermission} className="min-w-48">
          <Video className="mr-2 h-5 w-5" />
          Allow camera & microphone
        </Button>
      </div>
    );
  }

  if (state.phase === "READY") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-8 p-8">
        <div className="text-center">
          <h2 className="text-2xl font-bold">Camera is ready</h2>
          <p className="mt-2 text-muted-foreground">
            {questions.length} question{questions.length !== 1 ? "s" : ""} •
            Estimated {Math.ceil(questions.length * 2)} – {Math.ceil(questions.length * 4)} minutes
          </p>
        </div>

        <CameraPreview stream={streamRef.current} active={state.cameraActive} />

        <Button
          size="lg"
          onClick={handleBeginInterview}
          disabled={startMutation.isPending}
          className="min-w-48"
        >
          {startMutation.isPending ? (
            <Loader2 className="mr-2 h-5 w-5 animate-spin" />
          ) : (
            <Mic className="mr-2 h-5 w-5" />
          )}
          Begin Interview
        </Button>
      </div>
    );
  }

  if (state.phase === "UPLOADING") {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
        <Loader2 className="h-12 w-12 animate-spin text-primary" />
        <div>
          <h2 className="text-2xl font-bold">Saving your interview…</h2>
          <p className="mt-2 text-muted-foreground">Uploading recording and submitting answers.</p>
        </div>
      </div>
    );
  }

  const currentQ = questions[state.currentQuestionIndex];
  const isLastQuestion = state.currentQuestionIndex === questions.length - 1;

  if (!currentQ) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col">
      {/* Header bar */}
      <div className="border-b bg-muted/30 px-6 py-3">
        <div className="mx-auto max-w-3xl">
          <ProgressBar
            current={state.currentQuestionIndex + 1}
            total={questions.length}
          />
        </div>
      </div>

      {/* Main content */}
      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 p-6">

        {/* Camera + status row */}
        <div className="flex items-start gap-4">
          <CameraPreview stream={streamRef.current} active={state.cameraActive} />

          <div className="flex flex-1 flex-col gap-3 pt-2">
            {/* Status indicator */}
            <div
              className={cn(
                "inline-flex items-center gap-2 self-start rounded-full px-3 py-1.5 text-sm font-medium",
                state.isSpeaking && "bg-blue-100 text-blue-700",
                state.isListening && "bg-red-100 text-red-700",
                state.phase === "REVIEWING" && "bg-green-100 text-green-700",
              )}
            >
              {state.isSpeaking && (
                <>
                  <Volume2 className="h-4 w-4 animate-pulse" />
                  AI is speaking…
                </>
              )}
              {state.isListening && (
                <>
                  <Mic className="h-4 w-4 animate-pulse" />
                  Listening… speak your answer
                </>
              )}
              {state.phase === "REVIEWING" && (
                <>
                  <CheckCircle2 className="h-4 w-4" />
                  Review your answer below
                </>
              )}
            </div>

            {/* Proctoring warning */}
            {state.proctoring.tabSwitchCount > 0 && (
              <p className="text-xs text-amber-600">
                ⚠ {state.proctoring.tabSwitchCount} tab switch{state.proctoring.tabSwitchCount > 1 ? "es" : ""} detected
              </p>
            )}
          </div>
        </div>

        {/* Question card */}
        <Card className="flex-1">
          <CardContent className="flex flex-col gap-5 pt-6">
            {/* Dimension badge */}
            <div className="flex items-center gap-2">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
                {state.currentQuestionIndex + 1}
              </span>
              {currentQ.dimension && (
                <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                  {currentQ.dimension.replace(/_/g, " ")}
                </span>
              )}
            </div>

            {/* Question text */}
            <p className="text-xl font-medium leading-relaxed">{currentQ.text}</p>

            {/* Transcript area — editable */}
            <div className="space-y-1.5">
              <label htmlFor="transcript" className="text-sm font-medium text-muted-foreground">
                Your answer {state.isListening ? "(listening…)" : "(edit if needed)"}
              </label>
              <textarea
                id="transcript"
                rows={6}
                placeholder={
                  state.isSpeaking
                    ? "Listening will start after the question is read…"
                    : state.isListening
                    ? "Speak now — your words will appear here"
                    : "Speak your answer or type it here…"
                }
                className="flex w-full resize-none rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                value={state.currentTranscript}
                onChange={(e) =>
                  dispatch({ type: "UPDATE_TRANSCRIPT", text: e.target.value })
                }
                disabled={state.isSpeaking}
              />
              <p className="text-right text-xs text-muted-foreground">
                {state.currentTranscript.length} characters
              </p>
            </div>
          </CardContent>
        </Card>

        {/* Controls */}
        <div className="flex items-center justify-between gap-4">
          <div className="flex gap-2">
            {/* Stop listening / restart */}
            {state.isListening && (
              <Button variant="outline" onClick={stopListening} size="sm">
                <MicOff className="mr-1 h-4 w-4" />
                Stop listening
              </Button>
            )}
            {state.phase === "REVIEWING" && (
              <Button variant="outline" onClick={startListening} size="sm">
                <Mic className="mr-1 h-4 w-4" />
                Re-record answer
              </Button>
            )}
            {/* Skip TTS */}
            {state.isSpeaking && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  synthRef.current.cancel();
                  dispatch({ type: "SET_SPEAKING", value: false });
                  startListening();
                }}
              >
                <SkipForward className="mr-1 h-4 w-4" />
                Skip reading
              </Button>
            )}
          </div>

          {/*
            No UPLOADING check here: the component returns early for that phase
            further up, so by this point the phase can only be SPEAKING,
            LISTENING or REVIEWING.
          */}
          <Button
            onClick={handleNextQuestion}
            disabled={state.isSpeaking || completeMutation.isPending}
            className="min-w-36"
          >
            {completeMutation.isPending ? (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            ) : (
              <ChevronRight className="mr-2 h-4 w-4" />
            )}
            {isLastQuestion ? "Finish interview" : "Next question"}
          </Button>
        </div>
      </div>
    </div>
  );
}
