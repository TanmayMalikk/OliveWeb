import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowDown, ArrowLeft, ArrowRight, ArrowDownToLine, LayoutGrid, Pause, RotateCcw, RotateCw, Repeat2 } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  advanceTetris, HIDDEN_ROWS, MARATHON_GOAL, TETRIS_WIDTH, landingPiece, newTetris, pieceCells, pieceOffsets,
  stepTetris, tetrisLevel, type TetrisAction, type TetrisWorld, type Tetromino,
} from "./tetris-rules";

type Phase = "ready" | "playing" | "paused" | "over" | "won";
const BEST_KEY = "grove-tetris-marathon-best";
type Horizontal = "left" | "right";
const REPEAT_DELAY = 300;
const REPEAT_INTERVAL = 27;
const KEYS: Record<string, TetrisAction> = {
  ArrowLeft: "left", ArrowRight: "right", ArrowDown: "down",
  ArrowUp: "cw", x: "cw", z: "ccw", Control: "ccw", " ": "drop", c: "hold", Shift: "hold",
};
const CONTROLS = [
  ["left", "Move left", ArrowLeft, "←"], ["right", "Move right", ArrowRight, "→"],
  ["cw", "Rotate right", RotateCw, "↑ / X"], ["ccw", "Rotate left", RotateCcw, "Z / Ctrl"],
  ["down", "Soft drop", ArrowDown, "↓"], ["drop", "Hard drop", ArrowDownToLine, "Space"],
  ["hold", "Hold piece", Repeat2, "C / Shift"],
] as const;

function Preview({ label, piece }: { label: string; piece: Tetromino | null }) {
  const offsets = piece ? pieceOffsets(piece) : [];
  const minY = offsets.length ? Math.min(...offsets.map(([, y]) => y)) : 0;
  const shiftX = piece === "O" ? 1 : 0;
  const cells = new Set(offsets.map(([x, y]) => (y - minY) * 4 + x + shiftX));
  return (
    <div className="tetris-preview" aria-label={`${label}: ${piece ?? "empty"}`}>
      <strong className={label.startsWith("Upcoming") ? "sr-only" : undefined}>{label}</strong>
      <div className="tetris-preview-grid" aria-hidden="true">
        {Array.from({ length: 8 }, (_, i) =>
          <span key={i} className={cells.has(i) ? `tetris-cell tetris-${piece}` : "tetris-cell"} />)}
      </div>
    </div>
  );
}

export default function GroveTetris() {
  const [world, setWorld] = useState(newTetris);
  const current = useRef(world);
  const [phase, setPhase] = useState<Phase>("ready");
  const [best, setBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);
  const stage = useRef<HTMLDivElement>(null);
  const heldHorizontal = useRef<Horizontal[]>([]);
  const heldDown = useRef(false);
  const repeatAt = useRef(0);
  const level = tetrisLevel(world.lines);

  const clearInput = useCallback(() => {
    heldHorizontal.current = [];
    heldDown.current = false;
  }, []);

  const publish = useCallback((next: TetrisWorld) => {
    const previous = current.current;
    if (previous === next) return;
    current.current = next;
    if (next.board !== previous.board || next.piece !== previous.piece ||
        next.held !== previous.held || next.score !== previous.score || next.over || next.won) {
      setWorld(next);
    }
    if (next.won) {
      gameSfx.win();
      setPhase("won");
    } else if (next.over) {
      gameSfx.miss();
      setPhase("over");
    } else if (next.lines > previous.lines) {
      gameSfx.score();
    }
  }, []);

  const act = useCallback((action: TetrisAction) => {
    publish(stepTetris(current.current, action));
  }, [publish]);

  useEffect(() => {
    let active = true;
    void readCover<number>(BEST_KEY, 0).catch(() => 0).then((value) => {
      if (!active) return;
      setBest(value);
      setStorageReady(true);
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!storageReady || world.score <= best) return;
    setBest(world.score);
    void writeCover(BEST_KEY, world.score).catch(() => {});
  }, [best, storageReady, world.score]);

  useEffect(() => {
    if (phase !== "playing") return;
    let frame: number;
    let previousFrame = performance.now();
    const tick = (now: number) => {
      const direction = heldHorizontal.current.at(-1);
      if (direction && now >= repeatAt.current) {
        act(direction);
        repeatAt.current += REPEAT_INTERVAL;
        if (repeatAt.current < now) repeatAt.current = now + REPEAT_INTERVAL;
      }
      publish(advanceTetris(current.current, Math.min(100, now - previousFrame), heldDown.current));
      previousFrame = now;
      if (!current.current.over && !current.current.won) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    const onVisibility = () => { if (document.hidden) setPhase("paused"); };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("blur", clearInput);
    return () => {
      cancelAnimationFrame(frame);
      clearInput();
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("blur", clearInput);
    };
  }, [act, clearInput, phase, publish]);

  const start = () => {
    const fresh = newTetris();
    current.current = fresh;
    setWorld(fresh);
    setPhase("playing");
    gameSfx.tap();
    stage.current?.focus();
  };
  const resume = () => {
    setPhase("playing");
    stage.current?.focus();
  };

  const index = (x: number, y: number) => y * TETRIS_WIDTH + x;
  const falling = new Set(world.over || world.won ? [] : pieceCells(world.piece).map(([x, y]) => index(x, y)));
  const ghost = new Set(world.over || world.won ? [] : pieceCells(landingPiece(world)).map(([x, y]) => index(x, y)));

  return (
    <section className="grove-game tetris-game" aria-label="Tetris game">
      <div className="game-score">
        <span>SCORE <strong>{world.score}</strong></span>
        <span>LINES <strong aria-live="polite">{world.lines} / {MARATHON_GOAL}</strong></span>
        <span>LEVEL <strong>{level}</strong></span>
        <span>YOUR BEST <strong>{best || "—"}</strong></span>
      </div>
      <div className="tetris-arena">
        <div
          className="tetris-stage"
          ref={stage}
          tabIndex={0}
          role="group"
          aria-label="Tetris board. Arrow keys to move, Up to rotate, Space to drop, C to hold, Escape to pause."
          onBlur={clearInput}
          onKeyDown={(event) => {
            if (event.target !== event.currentTarget || (event.ctrlKey && event.key !== "Control") || event.metaKey || event.altKey) return;
            if ((event.key === "Escape" || event.key.toLowerCase() === "p") && !event.repeat) {
              if (phase === "playing" || phase === "paused") {
                event.preventDefault();
                setPhase(phase === "playing" ? "paused" : "playing");
              }
              return;
            }
            const action = KEYS[event.key] ?? KEYS[event.key.toLowerCase()];
            if (!action || phase !== "playing") return;
            event.preventDefault();
            if (event.repeat) return;
            if (action === "left" || action === "right") {
              heldHorizontal.current = [...heldHorizontal.current.filter((held) => held !== action), action];
              repeatAt.current = performance.now() + REPEAT_DELAY;
            }
            if (action === "down") heldDown.current = true;
            act(action);
          }}
          onKeyUp={(event) => {
            const action = KEYS[event.key];
            if (action === "down") heldDown.current = false;
            if (action !== "left" && action !== "right") return;
            const wasActive = heldHorizontal.current.at(-1) === action;
            heldHorizontal.current = heldHorizontal.current.filter((held) => held !== action);
            const remaining = heldHorizontal.current.at(-1);
            if (remaining && wasActive && phase === "playing") {
              act(remaining);
              repeatAt.current = performance.now() + REPEAT_DELAY;
            }
          }}
        >
          <aside className="tetris-previews tetris-hold">
            <Preview label="Hold" piece={world.held} />
            <Button variant="quiet" disabled={phase !== "playing"} onClick={() => setPhase("paused")}>
              <Pause size={16} aria-hidden="true" /> Pause <kbd>Esc</kbd>
            </Button>
            <output className="tetris-clear" aria-live="polite">{world.lastClear}</output>
          </aside>
          <div className="tetris-well" aria-hidden="true">
            {world.board.slice(HIDDEN_ROWS).flatMap((row, visibleY) => row.map((cell, x) => {
              const i = index(x, visibleY + HIDDEN_ROWS);
              const kind = falling.has(i) ? world.piece.kind : cell;
              const shadow = !kind && ghost.has(i);
              return <span key={i} className={`tetris-cell${kind ? ` tetris-${kind}` : ""}${shadow ? " tetris-ghost" : ""}`} />;
            }))}
          </div>
          <aside className="tetris-previews tetris-next" aria-label="Upcoming pieces">
            {world.queue.slice(0, 6).map((piece, i) =>
              <Preview key={i} label={i === 0 ? "Next" : `Upcoming ${i + 1}`} piece={piece} />)}
          </aside>
          {phase === "ready" && (
            <GameOverlay
              icon={<LayoutGrid size={22} />}
              title="Tetris Marathon"
              subtitle="Clear 150 lines across 15 levels."
              controlsHint="Arrow keys to move and rotate. Space to drop. C to hold."
              actionLabel="Start Tetris"
              onAction={start}
            />
          )}
          {phase === "paused" && (
            <GameOverlay tone="paused" title="Paused" subtitle="Your stack is saved for this round."
              actionLabel="Resume" onAction={resume} secondaryLabel="New game" onSecondary={start} />
          )}
          {(phase === "over" || phase === "won") && (
            <GameOverlay tone={phase === "won" ? "won" : "over"}
              title={phase === "won" ? "Marathon complete" : "Stack reached the top"}
              subtitle={`${world.lines} of ${MARATHON_GOAL} lines cleared.`}
              score={world.score} best={best} actionLabel="Play again" onAction={start} />
          )}
        </div>
      </div>
      <div className="tetris-controls" role="group" aria-label="Tetris controls">
        {CONTROLS.map(([action, label, Icon, keys]) => (
          <button type="button" key={action} aria-label={label} disabled={phase !== "playing"} onClick={(event) => {
            act(action);
            if (event.detail > 0) stage.current?.focus();
          }}>
            <Icon size={17} aria-hidden="true" /> {label} <kbd aria-hidden="true">{keys}</kbd>
          </button>
        ))}
      </div>
    </section>
  );
}
