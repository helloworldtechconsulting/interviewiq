// =============================================================================
// speech.d.ts — Web Speech API type declarations
//
// SpeechRecognition is not in TypeScript's lib.dom, because it has never been
// standardised beyond a W3C Community Group draft and ships only in Chromium
// under the webkit-prefixed name. The interview room depends on it directly:
// PRD v2.1 §9 selects the browser Web Speech API for speech-to-text precisely
// because it is free, needs no model download, keeps audio off our servers, and
// is viable given the Chromium-only requirement (§8, browser support).
//
// These declarations cover only the surface the room actually uses. Anything
// broader would be inventing API we do not call.
// =============================================================================

interface SpeechRecognitionAlternative {
  readonly transcript: string;
  readonly confidence: number;
}

interface SpeechRecognitionResult {
  readonly isFinal: boolean;
  readonly length: number;
  item(index: number): SpeechRecognitionAlternative;
  [index: number]: SpeechRecognitionAlternative;
}

interface SpeechRecognitionResultList {
  readonly length: number;
  item(index: number): SpeechRecognitionResult;
  [index: number]: SpeechRecognitionResult;
}

interface SpeechRecognitionEvent extends Event {
  /** Index of the first result that changed in this event. */
  readonly resultIndex: number;
  readonly results: SpeechRecognitionResultList;
}

type SpeechRecognitionErrorCode =
  | "no-speech"
  | "aborted"
  | "audio-capture"
  | "network"
  | "not-allowed"
  | "service-not-allowed"
  | "bad-grammar"
  | "language-not-supported";

interface SpeechRecognitionErrorEvent extends Event {
  readonly error: SpeechRecognitionErrorCode;
  readonly message: string;
}

interface SpeechRecognition extends EventTarget {
  /** BCP-47 tag, e.g. "en-US". */
  lang: string;
  /** Keep recognising across pauses rather than stopping at the first result. */
  continuous: boolean;
  /** Emit provisional results so the candidate can see they are being heard. */
  interimResults: boolean;
  maxAlternatives: number;

  start(): void;
  stop(): void;
  abort(): void;

  onresult: ((this: SpeechRecognition, ev: SpeechRecognitionEvent) => void) | null;
  onerror: ((this: SpeechRecognition, ev: SpeechRecognitionErrorEvent) => void) | null;
  onend: ((this: SpeechRecognition, ev: Event) => void) | null;
  onstart: ((this: SpeechRecognition, ev: Event) => void) | null;
  onspeechend: ((this: SpeechRecognition, ev: Event) => void) | null;
}

declare var SpeechRecognition: {
  prototype: SpeechRecognition;
  new (): SpeechRecognition;
};

interface Window {
  /** Present in Chromium behind the webkit prefix; absent in Safari and Firefox. */
  SpeechRecognition?: typeof SpeechRecognition;
  webkitSpeechRecognition?: typeof SpeechRecognition;
}
