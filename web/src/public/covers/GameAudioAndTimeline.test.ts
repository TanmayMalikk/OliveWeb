// @vitest-environment happy-dom
import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installDomMocks } from "../../test-utils/domTestHarness";
import { gameSfx, isGameMuted, setGameMuted } from "./game-audio";

const mockSubscribeKey = vi.fn();
const mockSubscribeEvents = vi.fn();

vi.mock("../../services/MessagingService", () => ({
  subscribeChatDeviceKey: (...args: unknown[]) => mockSubscribeKey(...args),
  subscribeMembershipEvents: (...args: unknown[]) => mockSubscribeEvents(...args),
}));

import { useChatDeviceKey, useChatTimeline } from "../../users/shared/useChatTimeline";

describe("game-audio and useChatTimeline", () => {
  beforeEach(() => {
    installDomMocks();
    setGameMuted(false);
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    setGameMuted(false);
  });

  it("plays all game sound effects and respects mute state", () => {
    vi.useFakeTimers();
    expect(isGameMuted()).toBe(false);
    gameSfx.tap();
    gameSfx.flap();
    gameSfx.hop();
    gameSfx.bounce();
    gameSfx.score();
    gameSfx.match();
    gameSfx.merge(3);
    gameSfx.powerup();
    gameSfx.coin();
    gameSfx.splash();
    gameSfx.miss();
    gameSfx.win();
    vi.advanceTimersByTime(500);

    setGameMuted(true);
    expect(isGameMuted()).toBe(true);
    gameSfx.tap();
  });

  it("observes chat device keys and merges messages with membership timeline events", async () => {
    mockSubscribeKey.mockImplementation(async (_cid: string, _uid: string, cb: (ok: boolean) => void) => {
      cb(true);
      return () => {};
    });
    mockSubscribeEvents.mockImplementation(async (_cid: string, cb: (events: Array<{ id: string; text: string; createdAt: number }>) => void) => {
      cb([{ id: "e1", text: "Joined", createdAt: 150 }]);
      return () => {};
    });

    const { result: keyResult } = renderHook(() => useChatDeviceKey("c1", "u1"));
    await waitFor(() => expect(keyResult.current).toBe(true));

    const { result: timelineResult } = renderHook(() =>
      useChatTimeline("c1", "u1", [
        { id: "m1", senderUid: "u1", senderUsername: "u1", body: "Hi", deleted: false, clientSentAt: 100 },
        { id: "m2", senderUid: "u2", senderUsername: "u2", body: "Hey", deleted: false, clientSentAt: 200 },
      ]),
    );
    await waitFor(() => expect(timelineResult.current).toHaveLength(3));
    expect(timelineResult.current.map((item) => item.kind)).toEqual(["message", "membership", "message"]);
  });
});
