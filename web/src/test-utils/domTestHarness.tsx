import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { vi } from "vitest";
import { emptyHistory, emptyProfile } from "../domain/DomainModels";
import { SessionContext, type SessionValue } from "../session/SessionContext";

let installed = false;

export function installDomMocks() {
  if (installed) return;
  installed = true;

  if (typeof HTMLCanvasElement !== "undefined") {
    const gradientStub = { addColorStop: vi.fn() };
    const ctxStub = new Proxy(
      {
        canvas: { width: 400, height: 300 },
        fillStyle: "",
        strokeStyle: "",
        lineWidth: 1,
        lineCap: "butt",
        lineJoin: "miter",
        globalAlpha: 1,
        font: "12px sans-serif",
        textAlign: "left",
        textBaseline: "alphabetic",
        shadowColor: "transparent",
        shadowBlur: 0,
        shadowOffsetX: 0,
        shadowOffsetY: 0,
        fillRect: vi.fn(),
        clearRect: vi.fn(),
        strokeRect: vi.fn(),
        beginPath: vi.fn(),
        closePath: vi.fn(),
        moveTo: vi.fn(),
        lineTo: vi.fn(),
        arc: vi.fn(),
        ellipse: vi.fn(),
        rect: vi.fn(),
        roundRect: vi.fn(),
        quadraticCurveTo: vi.fn(),
        bezierCurveTo: vi.fn(),
        fill: vi.fn(),
        stroke: vi.fn(),
        clip: vi.fn(),
        save: vi.fn(),
        restore: vi.fn(),
        translate: vi.fn(),
        scale: vi.fn(),
        rotate: vi.fn(),
        setTransform: vi.fn(),
        resetTransform: vi.fn(),
        setLineDash: vi.fn(),
        fillText: vi.fn(),
        strokeText: vi.fn(),
        measureText: vi.fn(() => ({ width: 24 })),
        createLinearGradient: vi.fn(() => gradientStub),
        createRadialGradient: vi.fn(() => gradientStub),
        drawImage: vi.fn(),
        getImageData: vi.fn(() => ({ data: new Uint8ClampedArray(16), width: 2, height: 2 })),
        putImageData: vi.fn(),
      },
      {
        get(target, prop) {
          if (prop in target) return (target as Record<PropertyKey, unknown>)[prop];
          return vi.fn();
        },
      },
    );
    Object.defineProperty(HTMLCanvasElement.prototype, "getContext", {
      configurable: true,
      value: vi.fn(() => ctxStub),
    });
    Object.defineProperty(HTMLCanvasElement.prototype, "toDataURL", {
      configurable: true,
      value: vi.fn(() => "data:image/png;base64,iVBORw0KGgo="),
    });
  }

  class MockAudioNode {
    gain = { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn(), value: 1 };
    frequency = { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn(), value: 440 };
    type: OscillatorType = "sine";
    connect = vi.fn(() => this);
    start = vi.fn();
    stop = vi.fn();
  }
  class MockAudioContext {
    state = "running";
    currentTime = 0;
    destination = {};
    resume = vi.fn(async () => {});
    createOscillator = vi.fn(() => new MockAudioNode());
    createGain = vi.fn(() => new MockAudioNode());
  }
  Object.defineProperty(globalThis, "AudioContext", { configurable: true, writable: true, value: MockAudioContext });
  Object.defineProperty(globalThis, "webkitAudioContext", { configurable: true, writable: true, value: MockAudioContext });

  if (typeof globalThis.ResizeObserver === "undefined") {
    globalThis.ResizeObserver = class {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn();
    } as unknown as typeof ResizeObserver;
  }
  if (typeof globalThis.IntersectionObserver === "undefined") {
    globalThis.IntersectionObserver = class {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn();
    } as unknown as typeof IntersectionObserver;
  }
  if (typeof window !== "undefined") {
    if (!window.matchMedia) {
      window.matchMedia = vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }));
    }
    window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
  }
  if (typeof Element !== "undefined") {
    Element.prototype.scrollIntoView = vi.fn();
  }
  if (!URL.createObjectURL) {
    URL.createObjectURL = vi.fn(() => "blob:test-url");
  }
  if (!URL.revokeObjectURL) {
    URL.revokeObjectURL = vi.fn();
  }
}

export function createMockSession(overrides: Partial<SessionValue> = {}): SessionValue {
  const defaultProfile = emptyProfile("user_one", "USER");
  return {
    path: "/",
    go: vi.fn(),
    phase: "safe",
    personalLink: "https://yourolivebranch.ca/#token123456",
    hasEntryToken: false,
    profile: defaultProfile,
    history: emptyHistory(),
    enrollment: {
      uid: "uid-1",
      username: "user_one",
      webCover: "GAME",
      role: "USER",
      triggerHash: "",
      pinKey: { salt: "s", box: { iv: "i", data: "d" } },
      pinLength: 6,
      failures: 0,
    },
    page: "Home",
    setPage: vi.fn(),
    requestedConversation: null,
    setRequestedConversation: vi.fn(),
    busy: false,
    message: "",
    clearMessage: vi.fn(),
    perform: vi.fn(async (work) => {
      try {
        await work();
      } catch {
        /* errors inside perform are handled via session.message in SessionProvider */
      }
    }),
    authenticate: vi.fn(async () => {}),
    completeSetup: vi.fn(async () => {}),
    beginEntry: vi.fn(async () => {}),
    quickExit: vi.fn(),
    unlock: vi.fn(async () => {}),
    recoverEncryptedAccess: vi.fn(async () => {}),
    enablePasswordRecovery: vi.fn(async () => {}),
    refreshPasswordRecoveryStatus: vi.fn(async () => {}),
    hasEncryptedAccess: true,
    passwordRecoveryEnrolled: true,
    passwordRecoveryKeysetExists: true,
    saveProfile: vi.fn(async () => {}),
    saveEntry: vi.fn(async () => {}),
    record: vi.fn(async () => {}),
    refresh: vi.fn(async () => {}),
    signOut: vi.fn(async () => {}),
    idleSeconds: null,
    idleTimeoutSeconds: 60,
    setIdleTimeoutSeconds: vi.fn(async () => {}),
    noteInteraction: vi.fn(),
    cloudMode: true,
    onboardingProfile: null,
    ...overrides,
  };
}

export function renderWithSession(ui: ReactElement, overrides: Partial<SessionValue> = {}) {
  const session = createMockSession(overrides);
  const result = render(<SessionContext.Provider value={session}>{ui}</SessionContext.Provider>);
  return { ...result, session };
}
