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
import { Button } from "../components";
import { readCover, writeCover } from "../services/cover-storage";
import {
  CROSSING,
  advanceCrossing,
  moveCrossing,
  newCrossing,
  type CrossingDirection,
} from "./olive-crossing";

type CrossingPhase = "ready" | "running" | "paused" | "over";

const laneY = (row: number) => row * CROSSING.cell;

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
          <svg
            viewBox={`${camera.x} 0 ${camera.width} ${CROSSING.rows * CROSSING.cell}`}
            aria-hidden="true"
          >
            {world.lanes.map((lane, row) => {
              const y = laneY(row);
              return (
                <g key={lane.id} className="crossing-lane">
                  <rect
                    x="0"
                    y={y}
                    width={CROSSING.columns * CROSSING.cell}
                    height="50"
                    fill={
                      lane.type === "road"
                        ? "#8b887c"
                        : lane.type === "rail"
                          ? "#867a69"
                          : lane.type === "water"
                            ? "#78aaa8"
                            : row % 2
                              ? "#b8cb8d"
                              : "#a9c17e"
                    }
                  />
                  {lane.type === "road" || lane.type === "rail" ? (
                    <>
                      {lane.type === "road" ? (
                        <path
                          d={`M0 ${y + 25}H${CROSSING.columns * CROSSING.cell}`}
                          stroke="#d9d2aa"
                          strokeWidth="2"
                          strokeDasharray="14 12"
                          opacity=".7"
                        />
                      ) : (
                        <>
                          {Array.from(
                            { length: CROSSING.columns * 2 },
                            (_, tie) => (
                              <rect
                                key={tie}
                                x={tie * 25 + 4}
                                y={y + 5}
                                width="5"
                                height="40"
                                rx="1"
                                fill="#574c3e"
                                opacity=".75"
                              />
                            ),
                          )}
                          <path
                            d={`M0 ${y + 13}H${CROSSING.columns * CROSSING.cell}M0 ${y + 37}H${CROSSING.columns * CROSSING.cell}`}
                            stroke="#d1c5aa"
                            strokeWidth="4"
                          />
                        </>
                      )}
                      {lane.vehicles.map((vehicle) => (
                        <g
                          key={vehicle.id}
                          transform={`translate(${vehicle.x * CROSSING.cell} ${y + 8})`}
                        >
                          <ellipse
                            className="crossing-dust"
                            cx={
                              lane.direction === 1
                                ? -3
                                : vehicle.width * CROSSING.cell + 3
                            }
                            cy="29"
                            rx="8"
                            ry="3"
                            fill="#d9d2aa"
                          />
                          {vehicle.kind === "train" ? (
                            <g className="crossing-vehicle-body crossing-train">
                              <rect
                                x={CROSSING.vehicleInset * CROSSING.cell}
                                y="-2"
                                width={
                                  (vehicle.width - CROSSING.vehicleInset * 2) *
                                  CROSSING.cell
                                }
                                height="36"
                                rx="5"
                                fill="#704f42"
                                stroke="#3e3931"
                                strokeWidth="2"
                              />
                              {Array.from(
                                {
                                  length: Math.max(
                                    2,
                                    Math.floor(vehicle.width),
                                  ),
                                },
                                (_, window) => (
                                  <rect
                                    key={window}
                                    x={18 + window * 36}
                                    y="6"
                                    width="19"
                                    height="11"
                                    rx="2"
                                    fill="#dbe4d2"
                                  />
                                ),
                              )}
                              <path
                                d={`M8 25H${vehicle.width * CROSSING.cell - 8}`}
                                stroke="#c89d59"
                                strokeWidth="4"
                              />
                            </g>
                          ) : (
                            <g className="crossing-vehicle-body">
                              <rect
                                x={CROSSING.vehicleInset * CROSSING.cell}
                                width={
                                  (vehicle.width - CROSSING.vehicleInset * 2) *
                                  CROSSING.cell
                                }
                                height="31"
                                rx="7"
                                fill={
                                  vehicle.kind === "truck"
                                    ? "#c78b4d"
                                    : "#61744a"
                                }
                                stroke="#3e4934"
                                strokeWidth="2"
                              />
                              <rect
                                x="8"
                                y="5"
                                width="14"
                                height="9"
                                rx="2"
                                fill="#dce7d2"
                              />
                              <circle
                                className="crossing-wheel"
                                cx="10"
                                cy="32"
                                r="5"
                                fill="#35382f"
                              />
                              <circle
                                className="crossing-wheel"
                                cx={vehicle.width * CROSSING.cell - 10}
                                cy="32"
                                r="5"
                                fill="#35382f"
                              />
                              {vehicle.kind === "truck" && (
                                <ellipse
                                  cx={vehicle.width * CROSSING.cell - 17}
                                  cy="15"
                                  rx="7"
                                  ry="10"
                                  fill="#52643a"
                                />
                              )}
                            </g>
                          )}
                        </g>
                      ))}
                    </>
                  ) : lane.type === "water" ? (
                    <>
                      {[14, 34].map((offset) => (
                        <path
                          key={offset}
                          className="crossing-wave"
                          d={`M0 ${y + offset}H${CROSSING.columns * CROSSING.cell}`}
                          stroke="#c9e2d8"
                          strokeWidth="2"
                          strokeDasharray="18 22"
                          opacity=".55"
                        />
                      ))}
                      {lane.pads.map((column) => (
                        <g
                          key={column}
                          className="crossing-lilypad"
                          transform={`translate(${column * CROSSING.cell + 25} ${y + 25})`}
                        >
                          <ellipse
                            rx="18"
                            ry="12"
                            fill="#668f55"
                            stroke="#496d42"
                            strokeWidth="2"
                          />
                          <path d="M0 0L18-8V8Z" fill="#78aaa8" />
                          {column % 5 === 0 && (
                            <circle cx="-4" cy="-4" r="4" fill="#f3e5d5" />
                          )}
                        </g>
                      ))}
                    </>
                  ) : (
                    Array.from(
                      { length: Math.ceil(CROSSING.columns / 2) },
                      (_, tree) => {
                        const worldWidth = CROSSING.columns * CROSSING.cell;
                        const x =
                          ((lane.id * 83 + tree * 117) % (worldWidth - 20)) +
                          10;
                        return (
                          <g
                            key={tree}
                            className="crossing-tree"
                            transform={`translate(${x} ${y + 8})`}
                            opacity=".72"
                          >
                            <rect
                              x="-2"
                              y="17"
                              width="4"
                              height="16"
                              fill="#70583a"
                            />
                            <circle cy="13" r="11" fill="#6f8c50" />
                          </g>
                        );
                      },
                    )
                  )}
                </g>
              );
            })}
            <g
              className="crossing-player"
              transform={`translate(${world.playerColumn * CROSSING.cell + 25} ${world.playerRow * CROSSING.cell + 25})`}
            >
              <g key={hop} className="crossing-player-hop">
                <ellipse
                  rx="15"
                  ry="18"
                  fill="#53683b"
                  stroke="#34452c"
                  strokeWidth="3"
                />
                <path
                  d="M0-16Q1-28 11-29"
                  fill="none"
                  stroke="#67482f"
                  strokeWidth="3"
                  strokeLinecap="round"
                />
                <circle cx="-5" cy="-3" r="2" fill="#f8f6df" />
                <circle cx="5" cy="-3" r="2" fill="#f8f6df" />
              </g>
            </g>
          </svg>
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
        safe; watch for carts and trains.
      </p>
    </section>
  );
}
