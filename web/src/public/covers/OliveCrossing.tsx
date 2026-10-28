// Cover users: shows the olive crossing game.
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Pause,
  Play,
  RotateCcw,
} from "lucide-react";
import { Button } from "../../components/SharedComponents";
import CrossingBoard from "./OliveCrossingBoard";
import { readCover, writeCover } from "../../services/CoverStateStore";
import {
  CROSSING,
  advanceCrossing,
  moveCrossing,
  newCrossing,
  type CrossingDirection,
} from "./olive-crossing";

type CrossingPhase = "ready" | "running" | "paused" | "over";


export default function OliveCrossing() {
  const [world, setWorld] = useState(() => newCrossing());
  const [phase, setPhase] = useState<CrossingPhase>("ready");
  const [best, setBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);
  const [hop, setHop] = useState(0);
  const [camera, setCamera] = useState({
    x: 0,
    width: CROSSING.columns * CROSSING.cell,
  });
  const phaseRef = useRef(phase);
  const frameRef = useRef<number | null>(null);
  const lastFrame = useRef(0);
  const boardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const board = boardRef.current;
    if (!board) return;
    // Updates camera.
    const updateCamera = () => {
      const bounds = board.getBoundingClientRect();
      if (!bounds.width || !bounds.height) return;
      const fullWidth = CROSSING.columns * CROSSING.cell;
      const worldHeight = CROSSING.rows * CROSSING.cell;
      const width = Math.min(
        fullWidth,
        Math.max(
          7 * CROSSING.cell,
          worldHeight * (bounds.width / bounds.height),
        ),
      );
      const playerCenter =
        world.playerColumn * CROSSING.cell + CROSSING.cell / 2;
      const x = Math.max(
        0,
        Math.min(fullWidth - width, playerCenter - width / 2),
      );
      setCamera((current) =>
        Math.abs(current.x - x) < 0.1 && Math.abs(current.width - width) < 0.1
          ? current
          : { x, width },
      );
    };
    updateCamera();
    const observer = new ResizeObserver(updateCamera);
    observer.observe(board);
    return () => observer.disconnect();
  }, [world.playerColumn]);

  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  useEffect(() => {
    let live = true;
    void readCover<number>("olive-crossing-best", 0)
      .then((value) => {
        if (live) {
          setBest(value);
          setStorageReady(true);
        }
      })
      .catch(() => {
        if (live) setStorageReady(true);
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (!storageReady || world.score <= best) return;
    setBest(world.score);
    void writeCover("olive-crossing-best", world.score).catch(() => {});
  }, [best, storageReady, world.score]);

  const tick = useCallback((time: number) => {
    if (phaseRef.current !== "running") return;
    const elapsed = lastFrame.current ? (time - lastFrame.current) / 1000 : 0;
    lastFrame.current = time;
    setWorld((current) => {
      const next = advanceCrossing(current, elapsed);
      if (next.over) {
        phaseRef.current = "over";
        setPhase("over");
      }
      return next;
    });
    if (phaseRef.current === "running")
      frameRef.current = requestAnimationFrame(tick);
  }, []);

  useEffect(() => {
    if (phase !== "running") return;
    lastFrame.current = 0;
    frameRef.current = requestAnimationFrame(tick);
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
    };
  }, [phase, tick]);

  useEffect(() => {
    // Handles visibility.
    const handleVisibility = () => {
      if (document.hidden && phaseRef.current === "running") setPhase("paused");
    };
    document.addEventListener("visibilitychange", handleVisibility);
    return () =>
      document.removeEventListener("visibilitychange", handleVisibility);
  }, []);

  const begin = useCallback(() => {
    setWorld(newCrossing());
    setHop((value) => value + 1);
    setPhase("running");
    requestAnimationFrame(() => boardRef.current?.focus());
  }, []);

  const move = useCallback((direction: CrossingDirection) => {
    if (phaseRef.current !== "running") return;
    setHop((value) => value + 1);
    setWorld((current) => {
      const next = moveCrossing(current, direction);
      if (next.over) {
        phaseRef.current = "over";
        setPhase("over");
      }
      return next;
    });
  }, []);

  // Handles key.
  const handleKey = (key: string) => {
    const directions: Record<string, CrossingDirection> = {
      ArrowUp: "up",
      ArrowDown: "down",
      ArrowLeft: "left",
      ArrowRight: "right",
      w: "up",
      s: "down",
      a: "left",
      d: "right",
    };
    const direction = directions[key.toLowerCase()] ?? directions[key];
    if (direction) {
      move(direction);
      return true;
    }
    return false;
  };

  const status =
    phase === "ready"
      ? "Ready to cross the grove."
      : phase === "paused"
        ? "Paused. Take your time."
        : phase === "over"
          ? `The olive needs another route. You crossed ${world.score} lanes.`
          : `Crossing the grove. ${world.score} lanes cleared.`;

  return (
    <section
      className="grove-game crossing-game"
      aria-label="Olive Crossing game"
    >
      <div className="game-score">
        <span>
          LANES <strong aria-live="polite">{world.score}</strong>
        </span>
        <span>
          YOUR BEST <strong>{best || "—"}</strong>
        </span>
        <span>
          GOAL <strong>KEEP GOING</strong>
        </span>
      </div>
      <div className="crossing-arena">
        <div
          ref={boardRef}
          className={`crossing-stage${phase === "over" ? " crossing-crashed" : ""}`}
          role="group"
          aria-label="Olive Crossing board. Use arrow keys or WASD to move."
          tabIndex={0}
          onKeyDown={(event) => {
            if (handleKey(event.key)) event.preventDefault();
            if (event.key === " " || event.key === "Enter") {
              event.preventDefault();
              if (phaseRef.current === "running") setPhase("paused");
              else if (phaseRef.current === "paused") setPhase("running");
              else begin();
            }
          }}
        >
          <CrossingBoard camera={camera} hop={hop} world={world} />
          {(phase === "ready" || phase === "over") && (
            <div className="flight-overlay">
              <strong>
                {phase === "over" ? "Mind the crossing" : "Olive Crossing"}
              </strong>
              <span>
                {phase === "over"
                  ? `You crossed ${world.score} lanes. Ready to try again?`
                  : "Guide the olive across the grove paths without touching a cart."}
              </span>
              <Button
                onClick={begin}
                aria-label={
                  phase === "over"
                    ? "Play Olive Crossing again"
                    : "Start Olive Crossing"
                }
              >
                {phase === "over" ? (
                  <RotateCcw size={16} />
                ) : (
                  <Play size={16} />
                )}{" "}
                {phase === "over" ? "Play again" : "Start game"}
              </Button>
            </div>
          )}
        </div>
      </div>
      <div className="game-bottom crossing-bottom">
        <p role="status">{status}</p>
        <div className="crossing-actions">
          <div className="crossing-pad" aria-label="Move the olive">
            <button aria-label="Move up" onClick={() => move("up")}>
              <ArrowUp />
            </button>
            <button aria-label="Move left" onClick={() => move("left")}>
              <ArrowLeft />
            </button>
            <button aria-label="Move down" onClick={() => move("down")}>
              <ArrowDown />
            </button>
            <button aria-label="Move right" onClick={() => move("right")}>
              <ArrowRight />
            </button>
          </div>
          <div className="crossing-round-actions">
            <Button
              variant="quiet"
              disabled={phase === "ready" || phase === "over"}
              onClick={() =>
                setPhase(phase === "running" ? "paused" : "running")
              }
            >
              {phase === "running" ? <Pause size={16} /> : <Play size={16} />}
              {phase === "running" ? "Pause" : "Resume"}
            </Button>
            <Button variant="quiet" onClick={begin}>
              <RotateCcw size={16} /> New crossing
            </Button>
          </div>
        </div>
      </div>
      <p className="flight-instructions">
        Use arrow keys, WASD, or the direction buttons. Grass and lily pads are
        safe. Railway lights warn when a train is approaching.
      </p>
    </section>
  );
}
