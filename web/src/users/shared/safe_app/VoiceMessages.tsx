import { useEffect, useRef, useState } from "react";
import { Mic, Play } from "lucide-react";
import type { Identity } from "../../../domain/DomainModels";
import type { Conversation } from "../../../services/MessagingService";
import {
  openVoiceMessage, prepareVoiceMessage, publishVoiceMessage, uploadBrowserDocument,
} from "../../../services/MediaService";

const formats = [
  ["audio/mp4;codecs=mp4a.40.2", "m4a"],
  ["audio/webm;codecs=opus", "webm"],
  ["audio/ogg;codecs=opus", "ogg"],
  ["audio/mp4", "m4a"],
] as const;
const voiceMessageLimitMs = 5 * 60_000;

type Capture = { recorder: MediaRecorder; stream: MediaStream; timer: number };
type Draft = {
  file: File;
  url: string;
  mediaId?: string;
  message?: Awaited<ReturnType<typeof prepareVoiceMessage>>;
};

export function VoiceMessageComposer({ conversation, identity, roomKey, disabled }: {
  conversation: Conversation;
  identity: Identity;
  roomKey: CryptoKey | null;
  disabled: boolean;
}) {
  const capture = useRef<Capture | null>(null);
  const currentDraft = useRef<Draft | null>(null);
  const generation = useRef(0);
  const [phase, setPhase] = useState<"idle" | "requesting" | "recording" | "draft" | "sending">("idle");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState("");

  function release() {
    generation.current++;
    const active = capture.current;
    capture.current = null;
    if (active) {
      window.clearTimeout(active.timer);
      if (active.recorder.state !== "inactive") active.recorder.stop();
      active.stream.getTracks().forEach((track) => track.stop());
    }
    if (currentDraft.current) URL.revokeObjectURL(currentDraft.current.url);
    currentDraft.current = null;
  }

  function discard() {
    release();
    setDraft(null);
    setError("");
    setPhase("idle");
  }

  useEffect(() => {
    const onVisibility = () => { if (document.hidden) discard(); };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      release();
    };
  }, []);

  async function record() {
    const token = ++generation.current;
    setError("");
    const format = typeof MediaRecorder === "undefined" ? undefined
      : formats.find(([mime]) => MediaRecorder.isTypeSupported(mime));
    if (!format || !navigator.mediaDevices?.getUserMedia) {
      setError("Voice recording is unavailable in this browser.");
      return;
    }
    setPhase("requesting");
    let stream: MediaStream | undefined;
    try {
      const [mimeType, extension] = format;
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1 } });
      if (generation.current !== token || document.hidden) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      const recorder = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 32_000 });
      const chunks: Blob[] = [];
      recorder.ondataavailable = ({ data }) => { if (data.size) chunks.push(data); };
      recorder.onstop = () => {
        if (capture.current?.recorder !== recorder) return;
        window.clearTimeout(capture.current.timer);
        capture.current.stream.getTracks().forEach((track) => track.stop());
        capture.current = null;
        if (!chunks.length) {
          setError("No audio was recorded. Try again.");
          setPhase("idle");
          return;
        }
        const file = new File(chunks, `Voice message.${extension}`, { type: mimeType.split(";")[0] });
        const next = { file, url: URL.createObjectURL(file) };
        currentDraft.current = next;
        setDraft(next);
        setPhase("draft");
      };
      recorder.onerror = () => {
        if (generation.current !== token) return;
        discard();
        setError("Could not record audio. Try again.");
      };
      recorder.start();
      const timer = window.setTimeout(() => {
        if (recorder.state === "recording") recorder.stop();
      }, voiceMessageLimitMs);
      capture.current = { recorder, stream, timer };
      setPhase("recording");
    } catch {
      stream?.getTracks().forEach((track) => track.stop());
      if (generation.current !== token) return;
      setPhase("idle");
      setError("Could not access the microphone. Check your browser permissions.");
    }
  }

  async function send() {
    if (!draft || !roomKey || disabled || phase !== "draft") return;
    const token = generation.current;
    setPhase("sending");
    setError("");
    try {
      draft.mediaId ??= await uploadBrowserDocument(draft.file, identity);
      draft.message ??= await prepareVoiceMessage(draft.mediaId, conversation, roomKey);
      await publishVoiceMessage(draft.message, identity);
      if (generation.current === token) discard();
    } catch {
      if (generation.current === token) {
        setError("Could not send this voice message. Try again.");
        setPhase("draft");
      }
    }
  }

  return (
    <div className="voice-compose">
      <button type="button" className="text-link voice-control" onClick={() => void record()}
        disabled={disabled || phase !== "idle"} aria-label="Record a voice message">
        <Mic size={20} aria-hidden="true" />
      </button>
      {phase === "requesting" && <span role="status">Waiting for microphone access…</span>}
      {phase === "recording" && (
        <>
          <span role="status">Recording · up to 5 minutes</span>
          <button type="button" onClick={() => {
            const recorder = capture.current?.recorder;
            if (recorder?.state === "recording") recorder.stop();
          }}>Stop recording</button>
          <button type="button" onClick={discard}>Discard</button>
        </>
      )}
      {draft && phase === "draft" && (
        <>
          <audio controls preload="metadata" src={draft.url} aria-label="Preview voice message" />
          <button type="button" onClick={() => void send()} disabled={disabled}>Send voice message</button>
          <button type="button" onClick={discard}>Discard</button>
        </>
      )}
      {phase === "sending" && <span role="status">Sending voice message…</span>}
      {error && <span role="status">{error}</span>}
    </div>
  );
}

export function VoiceMessageBubble({ mediaId, identity }: { mediaId: string; identity: Identity }) {
  const openedUrl = useRef("");
  const generation = useRef(0);
  const audio = useRef<HTMLAudioElement>(null);
  const [url, setUrl] = useState("");
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const release = () => {
      generation.current++;
      audio.current?.pause();
      URL.revokeObjectURL(openedUrl.current);
      openedUrl.current = "";
    };
    const onVisibility = () => {
      if (!document.hidden) return;
      release();
      setUrl("");
      setLoading(false);
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      release();
    };
  }, []);

  async function open() {
    const token = ++generation.current;
    setLoading(true);
    setFailed(false);
    try {
      const next = await openVoiceMessage(mediaId, identity);
      if (generation.current !== token) {
        URL.revokeObjectURL(next);
        return;
      }
      openedUrl.current = next;
      setUrl(next);
    } catch {
      if (generation.current === token) setFailed(true);
    } finally {
      if (generation.current === token) setLoading(false);
    }
  }

  return (
    <div className="voice-message">
      {url ? (
        <audio ref={audio} controls autoPlay preload="metadata" src={url} aria-label="Voice message" />
      ) : (
        <button type="button" className="shared-message-card" disabled={loading} onClick={() => void open()}>
          <Play size={19} aria-hidden="true" /> {loading ? "Loading voice message…" : "Play voice message"}
        </button>
      )}
      {failed && <span role="status">Could not open this voice message.</span>}
    </div>
  );
}
