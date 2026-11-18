// Cover users: lightweight Web Audio chimes for Little Grove games.
let audioCtx: AudioContext | null = null;
let muted = false;

function getContext(): AudioContext | null {
  if (muted || typeof window === "undefined") return null;
  const Ctor =
    window.AudioContext ||
    (window as unknown as { webkitAudioContext?: typeof AudioContext })
      .webkitAudioContext;
  if (!Ctor) return null;
  if (!audioCtx) {
    try {
      audioCtx = new Ctor();
    } catch {
      return null;
    }
  }
  if (audioCtx.state === "suspended") {
    void audioCtx.resume().catch(() => {});
  }
  return audioCtx;
}

export function isGameMuted() {
  return muted;
}

export function setGameMuted(next: boolean) {
  muted = next;
}

export function playTone(
  frequency: number,
  duration = 0.08,
  type: OscillatorType = "sine",
  gainValue = 0.06,
  slideTo?: number,
) {
  const ctx = getContext();
  if (!ctx) return;
  try {
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(frequency, now);
    if (slideTo) {
      osc.frequency.exponentialRampToValueAtTime(
        Math.max(40, slideTo),
        now + duration,
      );
    }
    gain.gain.setValueAtTime(0.001, now);
    gain.gain.linearRampToValueAtTime(gainValue, now + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0008, now + duration);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + duration + 0.01);
  } catch {
    /* Ignore audio errors when autoplay is blocked. */
  }
}

export const gameSfx = {
  tap: () => playTone(420, 0.06, "triangle", 0.05, 520),
  flap: () => playTone(340, 0.09, "sine", 0.06, 560),
  hop: () => playTone(390, 0.07, "triangle", 0.055, 580),
  bounce: () => playTone(510, 0.06, "sine", 0.05, 620),
  score: () => {
    playTone(587.33, 0.08, "sine", 0.05);
    window.setTimeout(() => playTone(880, 0.11, "sine", 0.05), 55);
  },
  match: () => {
    playTone(523.25, 0.09, "triangle", 0.05);
    window.setTimeout(() => playTone(659.25, 0.11, "triangle", 0.05), 60);
    window.setTimeout(() => playTone(783.99, 0.14, "triangle", 0.045), 120);
  },
  merge: (level = 1) =>
    playTone(280 + Math.min(10, level) * 45, 0.09, "triangle", 0.055),
  powerup: () => {
    playTone(440, 0.07, "sine", 0.05);
    window.setTimeout(() => playTone(660, 0.09, "sine", 0.05), 60);
    window.setTimeout(() => playTone(880, 0.14, "sine", 0.05), 120);
  },
  coin: () => {
    playTone(987.77, 0.06, "sine", 0.055);
    window.setTimeout(() => playTone(1318.51, 0.12, "sine", 0.055), 55);
  },
  splash: () => playTone(260, 0.22, "sine", 0.06, 95),
  miss: () => playTone(210, 0.18, "triangle", 0.06, 110),
  win: () => {
    [523.25, 659.25, 783.99, 1046.5].forEach((freq, idx) => {
      window.setTimeout(() => playTone(freq, 0.15, "triangle", 0.055), idx * 75);
    });
  },
};
