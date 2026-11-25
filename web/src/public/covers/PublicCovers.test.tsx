// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installDomMocks } from "../../test-utils/domTestHarness";

const coverStorage = new Map<string, unknown>();

vi.mock("../../services/CoverStateStore", () => ({
  readCover: vi.fn(async (id: string, fallback: unknown) =>
    coverStorage.has(id) ? coverStorage.get(id) : fallback,
  ),
  writeCover: vi.fn(async (id: string, value: unknown) => {
    coverStorage.set(id, value);
  }),
}));

import { computePlayerPose, crossingCamera } from "./OliveCrossingBoard";
import OliveBreaker from "./OliveBreaker";
import OliveCrossing from "./OliveCrossing";
import OliveFlight from "./OliveFlight";
import GroveTetris from "./GroveTetris";
import GroveMerge from "./GroveMerge";
import Game from "./OliveGamesCover";
import { GameOverlay } from "./GameOverlay";
import PublicCoverPage from "../PublicCoverPage";
import DownloadPage from "../AppDownloadInstructions";
import { DownloadInvite } from "../DownloadInvite";
import { OLIVE_CHARACTERS, VoxelOlive, getOliveCharacter } from "./olive-crossing-characters";

describe("Public cover games, overlays, and download pages", () => {
  beforeEach(() => {
    installDomMocks();
    coverStorage.clear();
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    window.history.replaceState({}, "", "/");
  });

  it("renders OliveGamesCover, toggles audio, flips cards, and switches through all subgames", async () => {
    const { container } = render(<Game landing />);
    expect(container.querySelector("h1")?.textContent).toBe("Nature Pairs");

    // Flip two cards to trigger turn + timer, toggle audio mute, change board size.
    const card1 = screen.getByLabelText("Turn card 1");
    const card2 = screen.getByLabelText("Turn card 2");
    fireEvent.click(card1);
    fireEvent.click(card2);

    fireEvent.click(screen.getByLabelText(/game sounds/i));
    fireEvent.click(screen.getByLabelText(/game sounds/i));

    fireEvent.change(screen.getByLabelText("Board size"), { target: { value: "6" } });
    fireEvent.click(screen.getByText("New round"));

    // Hover/switch to lazy-loaded games.
    for (const label of ["Olive Flight", "Olive Crossing", "Canopy Breaker", "Grove 2048", "Tetris"]) {
      const btn = screen.getByRole("button", { name: label });
      fireEvent.mouseEnter(btn);
      fireEvent.click(btn);
      await waitFor(() => expect(container.querySelector("h1")?.textContent).toBe(label));
    }
  });

  it("drives OliveBreaker ready, serve, keyboard/pointer controls, pause, and resume", async () => {
    vi.useFakeTimers();
    render(<OliveBreaker />);
    await act(async () => {
      await vi.runOnlyPendingTimersAsync();
    });

    fireEvent.click(screen.getByLabelText("Start Canopy Breaker"));
    const stage = screen.getByRole("group", { name: /Canopy Breaker board/i });
    fireEvent.keyDown(stage, { key: " " }); // serve ball
    fireEvent.keyDown(stage, { key: "ArrowLeft" });
    await act(async () => {
      vi.advanceTimersByTime(220);
    });
    fireEvent.keyUp(stage, { key: "ArrowLeft" });
    fireEvent.keyDown(stage, { key: "ArrowRight" });
    await act(async () => {
      vi.advanceTimersByTime(120);
    });
    fireEvent.keyUp(stage, { key: "ArrowRight" });

    // Pointer controls + buttons + pause/resume + frame ticks + New round + visibility pause.
    const canvas = document.querySelector("canvas");
    if (canvas) {
      canvas.getBoundingClientRect = () =>
        ({ left: 0, top: 0, width: 320, height: 480, right: 320, bottom: 480, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      fireEvent.pointerMove(canvas, { clientX: 120, clientY: 120 });
      fireEvent.pointerDown(canvas, { clientX: 140, clientY: 120, button: 0 });
    }
    fireEvent.click(screen.getByLabelText("Move paddle left"));
    fireEvent.click(screen.getByLabelText("Move paddle right"));
    for (let i = 0; i < 180; i++) {
      await act(async () => {
        vi.advanceTimersByTime(40);
      });
    }
    const pauseBtn = screen.queryByRole("button", { name: "Pause" });
    if (pauseBtn) {
      fireEvent.click(pauseBtn);
      expect(screen.getByText("Paused")).toBeTruthy();
      fireEvent.click(screen.getAllByRole("button", { name: "Resume" })[0]);
    }
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    fireEvent.click(screen.getAllByRole("button", { name: /New round/i })[0]);
  });

  it("drives OliveCrossing characters, hop animation poses, keyboard, and D-pad controls", async () => {
    vi.useFakeTimers();
    coverStorage.set("olive-crossing-coins", 500);
    coverStorage.set("olive-crossing-character", "chef");

    const poseIdle = computePlayerPose(null, 0, true, 1.2);
    expect(poseIdle.jumpZ).toBe(0);
    const poseJump = computePlayerPose({ direction: "up", progress: 0.5, offsetX: 0, offsetY: 1 }, 1, false);
    expect(poseJump.jumpZ).toBeGreaterThan(0);
    const cam = crossingCamera(3, 1);
    expect(cam.unproject(0, 0).x).toBeGreaterThan(0);

    // Render all character variants directly.
    const { container } = render(
      <svg>
        {OLIVE_CHARACTERS.map((char) => (
          <VoxelOlive key={char.id} character={getOliveCharacter(char.id)} facing="down" />
        ))}
      </svg>,
    );
    expect(container.querySelectorAll("svg").length).toBeGreaterThan(0);

    render(<OliveCrossing />);
    await act(async () => {
      await vi.runOnlyPendingTimersAsync();
    });

    // Unlock/buy locked character, swipe gestures, hawk timeout game-over, and restart.
    fireEvent.click(screen.getAllByLabelText(/Unlock|Select/i, { selector: "button:not([disabled])" })[0]);
    const lockedBtn = screen.getAllByLabelText(/Unlock/i).at(-1);
    if (lockedBtn) fireEvent.click(lockedBtn);
    fireEvent.click(screen.getByLabelText("Start Olive Crossing"));

    const board = screen.getByRole("group", { name: /Olive Crossing board/i });
    fireEvent.keyDown(board, { key: " " }); // pause immediately while alive
    expect(screen.getByText("Paused")).toBeTruthy();
    fireEvent.keyDown(board, { key: " " }); // resume via space
    fireEvent.keyDown(board, { key: " " }); // pause again
    fireEvent.click(screen.getByRole("button", { name: "Resume Olive Crossing" }));

    fireEvent.click(screen.getByLabelText("Move up"));
    fireEvent.click(screen.getByLabelText("Move left"));
    fireEvent.click(screen.getByLabelText("Move right"));
    fireEvent.click(screen.getByLabelText("Move down"));
    fireEvent.pointerDown(board, { clientX: 50, clientY: 120 });
    fireEvent.pointerUp(board, { clientX: 50, clientY: 20 });
    if (typeof DOMMatrix !== "undefined" && typeof DOMPoint !== "undefined") {
      const svgEl = board.querySelector("svg") as unknown as { getScreenCTM?: () => DOMMatrix } | null;
      if (svgEl) {
        svgEl.getScreenCTM = () => new DOMMatrix();
        fireEvent.pointerDown(board, { clientX: 50, clientY: 50 });
        fireEvent.pointerUp(board, { clientX: 51, clientY: 51 });
        fireEvent.pointerDown(board, { clientX: 50, clientY: 50 });
        fireEvent.pointerUp(board, { clientX: 250, clientY: 51 });
        fireEvent.pointerDown(board, { clientX: 50, clientY: 50 });
        fireEvent.pointerUp(board, { clientX: 50, clientY: 250 });
      }
    }
    fireEvent.pointerDown(board, { clientX: 50, clientY: 50 });
    fireEvent.pointerCancel(board);
    fireEvent.keyDown(board, { key: "w" });
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowUp" }));
    });
    // Advance past HAWK_LIMIT_SECONDS after moving forward so eagle timeout & game-over overlay trigger.
    fireEvent.click(screen.getByLabelText("Move up"));
    for (let i = 0; i < 65; i++) {
      await act(async () => {
        vi.advanceTimersByTime(50);
        vi.advanceTimersByTime(50);
      });
    }
    const playAgain = screen.queryByRole("button", { name: "Play Olive Crossing again" });
    if (playAgain) fireEvent.click(playAgain);
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    fireEvent.click(screen.getAllByRole("button", { name: "New crossing" })[0]);
  }, 20_000);

  it("drives OliveFlight, GroveTetris, and GroveMerge interactions cleanly", async () => {
    vi.useFakeTimers();

    // OliveFlight: non-zero canvas/stage sizing, visibility pause, flap bursts, game-over, restart.
    const widthDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
    const heightDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientHeight");
    Object.defineProperty(HTMLElement.prototype, "clientWidth", { configurable: true, value: 360 });
    Object.defineProperty(HTMLElement.prototype, "clientHeight", { configurable: true, value: 520 });
    try {
      render(<OliveFlight />);
      await act(async () => {
        await vi.runOnlyPendingTimersAsync();
      });
      fireEvent.pointerDown(screen.getByLabelText("Start Olive Flight"), { button: 0 });
      act(() => {
        window.dispatchEvent(new KeyboardEvent("keydown", { key: " " }));
      });
      for (let i = 0; i < 90; i++) {
        await act(async () => {
          vi.advanceTimersByTime(40);
        });
      }
      const pauseBtn = screen.queryAllByRole("button", { name: /Pause/i })[0];
      if (pauseBtn && !(pauseBtn as HTMLButtonElement).disabled) {
        fireEvent.click(pauseBtn);
        const resumeFlight = screen.queryByRole("button", { name: "Resume Olive Flight" });
        if (resumeFlight) fireEvent.click(resumeFlight);
      }
      Object.defineProperty(document, "hidden", { configurable: true, value: true });
      act(() => {
        document.dispatchEvent(new Event("visibilitychange"));
      });
      Object.defineProperty(document, "hidden", { configurable: true, value: false });
      fireEvent.click(screen.getAllByRole("button", { name: /New round/i })[0]);
      cleanup();
    } finally {
      if (widthDesc) Object.defineProperty(HTMLElement.prototype, "clientWidth", widthDesc);
      if (heightDesc) Object.defineProperty(HTMLElement.prototype, "clientHeight", heightDesc);
    }

    // GroveTetris
    render(<GroveTetris />);
    await act(async () => {
      await vi.runOnlyPendingTimersAsync();
    });
    fireEvent.click(screen.getByRole("button", { name: "Start Tetris" }));
    const tetrisBoard = screen.getByRole("group", { name: /Tetris board/i });
    fireEvent.keyDown(tetrisBoard, { key: "ArrowLeft" });
    fireEvent.keyDown(tetrisBoard, { key: "ArrowRight" });
    fireEvent.keyDown(tetrisBoard, { key: "ArrowUp" });
    fireEvent.keyDown(tetrisBoard, { key: "ArrowDown" });
    fireEvent.keyDown(tetrisBoard, { key: "c" });
    fireEvent.keyDown(tetrisBoard, { key: " " });
    await act(async () => {
      vi.advanceTimersByTime(120);
    });
    fireEvent.keyUp(tetrisBoard, { key: "ArrowDown" });
    fireEvent.keyUp(tetrisBoard, { key: "ArrowLeft" });
    fireEvent.keyDown(tetrisBoard, { key: "Escape" });
    expect(screen.getByText("Paused")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Resume" })[0]);
    cleanup();

    // GroveMerge: mock setPointerCapture + swipe all directions + buttons.
    Element.prototype.setPointerCapture = vi.fn();
    render(<GroveMerge />);
    await act(async () => {
      await vi.runOnlyPendingTimersAsync();
    });
    const mergeBoard = screen.getByRole("group", { name: /Grove 2048 board/i });
    fireEvent.keyDown(mergeBoard, { key: "ArrowLeft" });
    fireEvent.keyDown(mergeBoard, { key: "ArrowUp" });
    fireEvent.keyDown(mergeBoard, { key: "ArrowRight" });
    fireEvent.keyDown(mergeBoard, { key: "ArrowDown" });
    fireEvent.pointerDown(mergeBoard, { clientX: 0, clientY: 0, pointerId: 1, button: 0, isPrimary: true });
    fireEvent.pointerUp(mergeBoard, { clientX: 80, clientY: 0, pointerId: 1 });
    fireEvent.pointerDown(mergeBoard, { clientX: 80, clientY: 0, pointerId: 2, button: 0, isPrimary: true });
    fireEvent.pointerUp(mergeBoard, { clientX: 0, clientY: 0, pointerId: 2 });
    fireEvent.pointerDown(mergeBoard, { clientX: 0, clientY: 0, pointerId: 3, button: 0, isPrimary: true });
    fireEvent.pointerUp(mergeBoard, { clientX: 0, clientY: 80, pointerId: 3 });
    fireEvent.pointerDown(mergeBoard, { clientX: 0, clientY: 80, pointerId: 4, button: 0, isPrimary: true });
    fireEvent.pointerUp(mergeBoard, { clientX: 0, clientY: 0, pointerId: 4 });
    fireEvent.pointerDown(mergeBoard, { clientX: 0, clientY: 0, pointerId: 5, button: 0, isPrimary: true });
    fireEvent.pointerCancel(mergeBoard);
    fireEvent.click(screen.getByLabelText("Slide up"));
    fireEvent.click(screen.getByLabelText("Slide down"));
    fireEvent.click(screen.getByLabelText("Slide left"));
    fireEvent.click(screen.getByLabelText("Slide right"));
    fireEvent.click(screen.getAllByRole("button", { name: /New round/i })[0]);
  });

  it("renders PublicCoverPage tabs, hidden entry gesture, DownloadPage, DownloadInvite, and GameOverlay", async () => {
    const onEntry = vi.fn();
    render(<PublicCoverPage onPrivateEntry={onEntry} />);
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    const leafBtn = screen.getByRole("button", { name: "olive icon" });
    fireEvent.click(leafBtn);
    fireEvent.click(leafBtn);
    expect(onEntry).toHaveBeenCalledTimes(1);
    cleanup();

    window.history.replaceState({}, "", "/get?invite=ABCDEF1234");
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn(async () => {}) },
    });
    render(
      <>
        <DownloadPage />
        <DownloadInvite />
      </>,
    );
    expect(screen.getAllByText(/You have an invite/i).length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByRole("button", { name: "Copy code" })[0]);
    await waitFor(() => expect(screen.getAllByText("Code copied.").length).toBeGreaterThan(0));

    const onAction = vi.fn();
    const onSecondary = vi.fn();
    render(
      <GameOverlay
        tone="over"
        title="Done"
        subtitle="Sub"
        score={10}
        best={12}
        isNewBest
        actionLabel="Retry"
        onAction={onAction}
        secondaryLabel="Reset"
        onSecondary={onSecondary}
        controlsHint="Hint"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));
    expect(onAction).toHaveBeenCalled();
    expect(onSecondary).toHaveBeenCalled();
  });
});
