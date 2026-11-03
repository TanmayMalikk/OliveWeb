// Cover users: shows the olive flight game.
import { useEffect, useRef, useState } from "react";
import { Pause, Play, RotateCcw } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import {
  advanceFlight,
  branchPath,
  FLIGHT,
  leafPaths,
  newFlight,
  flightSpeedMultiplier,
  oliveAngle,
} from "./olive-flight";

// Shows the leafy branch view for web olive flight cover. It uses the current cover data to update what the user sees.
function LeafyBranch({
  x,
  end,
  down,
  seed,
}: {
  x: number;
  end: number;
  down: boolean;
  seed: number;
}) {
  const height = down ? end : FLIGHT.height - end;
  const variation = seed + (down ? 0.173 : 0.719);
  // Web olive flight cover: returns the random. It uses the current cover data to update what the user sees.
  const random = (index: number, salt = 0) => {
    const value =
      Math.sin(variation * 8191 + index * 127.1 + salt * 311.7) * 43758.5453;
    return value - Math.floor(value);
  };
  const sway = (random(0, 1) - 0.5) * 9;
  const branchShape = `M27 0 Q${10 + sway} ${height * 0.36} ${23 + sway * 0.25} ${height} L${35 + sway * 0.25} ${height} Q${24 + sway} ${height * 0.36} 27 0Z`;
  const leafCount = Math.max(1, Math.floor((height - 10) / 32));
  return (
    <g transform={`translate(${x},${end}) scale(1,${down ? -1 : 1})`}>
      <path
        d={branchShape}
        fill="#6f5b39"
        stroke="#4f4029"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <path
        d={branchPath(height)}
        stroke="#aa9871"
        strokeWidth="2.5"
        strokeLinecap="round"
        fill="none"
      />
      {Array.from({ length: leafCount }, (_, index) => {
        const y = Math.min(
          height - 12,
          24 + index * 32 + (random(index, 2) - 0.5) * 8,
        );
        const leftScale = 0.78 + random(index, 3) * 0.34;
        const rightScale = 0.78 + random(index, 4) * 0.34;
        const leftRotation = -18 + random(index, 5) * 25;
        const rightRotation = -7 + random(index, 6) * 25;
        const twigSide = random(index, 7) > 0.5 ? 1 : -1;
        const twigLength = 10 + random(index, 8) * 10;
        const leftColor = random(index, 9) > 0.5 ? "#91a36d" : "#738a55";
        const rightColor = random(index, 10) > 0.5 ? "#a7b584" : "#667d4b";
        return (
          <g key={index}>
            <path
              d={`M27 ${y - 2} L${27 + twigSide * twigLength} ${y + 10 + random(index, 11) * 7} L${27 + twigSide * 3} ${y + 9}Z`}
              fill="#5f4d31"
              stroke="#493b27"
              strokeWidth="0.8"
              strokeLinejoin="round"
            />
            <g transform={`translate(27,${y})`}>
              <path
                d={`M0 5 L${-17 * leftScale} ${21 * leftScale} M0 8 L${18 * rightScale} ${24 * rightScale}`}
                stroke="#526640"
                strokeWidth="1.2"
                fill="none"
              />
              <path
                d={leafPaths[0]}
                fill={leftColor}
                stroke="#586c43"
                strokeWidth="0.7"
                transform={`rotate(${leftRotation}) scale(${leftScale})`}
              />
              <path
                d={leafPaths[1]}
                fill={rightColor}
                stroke="#526640"
                strokeWidth="0.7"
                transform={`rotate(${rightRotation}) scale(${rightScale})`}
              />
            </g>
          </g>
        );
      })}
    </g>
  );
}

export default function OliveFlight() {
  const [world, setWorld] = useState(() =>
    newFlight(FLIGHT.width, Math.random),
  );
  const [phase, setPhase] = useState<"ready" | "playing" | "paused">("ready");
  const [best, setBest] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  const stage = useRef<HTMLButtonElement>(null);
  const [viewWidth, setViewWidth] = useState<number>(FLIGHT.width);
  const widthRef = useRef<number>(FLIGHT.width);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  // Update phase ref when information used by this screen changes.
  useEffect(() => {
    const element = stage.current;
    if (!element) return;
    const observer = new ResizeObserver(() => {
      if (!element.clientHeight) return;
      const width =
        (element.clientWidth / element.clientHeight) * FLIGHT.height;
      if (Math.abs(widthRef.current - width) < 0.1) return;
      widthRef.current = width;
      setViewWidth(width);
      if (phaseRef.current === "ready") setWorld(newFlight(width, Math.random));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  // Update width when information used by this screen changes.
  useEffect(() => {
    let live = true;
    void readCover<number | null>("olive-flight-best", null)
      .then((value) => {
        if (live) {
          setBest(value);
          setLoaded(true);
        }
      })
      .catch(() => {
        if (live) setLoaded(true);
      });
    return () => {
      live = false;
    };
  }, []);
  // Update live when information used by this screen changes.
  useEffect(() => {
    if (phase !== "playing" || world.over) return;
    let frame = 0,
      previous = 0;
    // returns the tick for web olive flight cover.
    function tick(time: number) {
      const elapsed = previous ? (time - previous) / 1000 : 0;
      if (elapsed)
        setWorld((current) =>
          advanceFlight(current, elapsed, Math.random, widthRef.current),
        );
      previous = time;
      frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [phase, world.over]);
  // Update elapsed when information used by this screen changes.
  useEffect(() => {
    // returns the hide for web olive flight cover. It listens for new data and stops listening when the screen closes.
    function hide() {
      if (document.hidden)
        setPhase((current) => (current === "playing" ? "paused" : current));
    }
    document.addEventListener("visibilitychange", hide);
    return () => document.removeEventListener("visibilitychange", hide);
  }, []);
  // Update hide when information used by this screen changes.
  useEffect(() => {
    if (loaded && world.over && world.score > (best ?? 0)) {
      setBest(world.score);
      void writeCover("olive-flight-best", world.score).catch(() => {});
    }
  }, [world.over, world.score, best, loaded]);
  // Web olive flight cover: returns the flap.
  function flap() {
    // world over or phase is ready is handled here by flap.
    if (world.over || phase === "ready") {
      setWorld({
        ...newFlight(widthRef.current, Math.random),
        velocity: FLIGHT.lift,
      });
      setPhase("playing");
    } else {
      setWorld((current) => ({ ...current, velocity: FLIGHT.lift }));
      setPhase("playing");
    }
  }
  const message = world.over
    ? `Round over. You passed ${world.score} ${world.score === 1 ? "branch" : "branches"}. Try again?`
    : phase === "paused"
      ? "Paused. Take your time."
      : phase === "ready"
        ? "Ready when you are. Tap to take flight."
        : `${world.score} ${world.score === 1 ? "branch" : "branches"} passed.`;
  return (
    <section className="grove-game flight-game" aria-label="Olive Flight game">
      <div className="game-score">
        <span>
          BRANCHES <strong>{world.score}</strong>
        </span>
        <span>
          YOUR BEST <strong>{best ?? "—"}</strong>
        </span>
        <span>
          SPEED{" "}
          <strong>{flightSpeedMultiplier(world.score).toFixed(1)}×</strong>
        </span>
      </div>
      <div className="flight-arena">
        <button
          ref={stage}
          aria-describedby="flight-help"
          className="flight-stage"
          onPointerDown={(event) => {
            if (event.button === 0) {
              event.preventDefault();
              stage.current?.focus();
              flap();
            }
          }}
          onClick={(event) => {
            if (event.detail === 0) flap();
          }}
          aria-label={
            world.over
              ? "Play Olive Flight again"
              : phase === "ready"
                ? "Start Olive Flight"
                : phase === "paused"
                  ? "Resume Olive Flight"
                  : "Flap olive: press Space or Enter"
          }
        >
          <svg viewBox={`0 0 ${viewWidth} 480`} aria-hidden="true">
            <defs>
              <linearGradient id="flight-sky" x2="0" y2="1">
                <stop stopColor="#e6eddb" />
                <stop offset="1" stopColor="#faf8ec" />
              </linearGradient>
              <radialGradient id="flight-olive" cx=".3" cy=".25">
                <stop stopColor="#9aa86a" />
                <stop offset="1" stopColor="#485e34" />
              </radialGradient>
            </defs>
            <rect width={viewWidth} height={480} fill="url(#flight-sky)" />
            <circle cx={viewWidth * 0.8} cy="80" r="36" fill="#fff7da" />
            <path
              d={`M-50 455 Q${viewWidth * 0.2} 325 ${viewWidth * 0.55} 446 Q${viewWidth * 0.85} 340 ${viewWidth + 60} 445 V480 H-50Z`}
              fill="#dce4cb"
            />
            <path
              d={`M-40 480 Q${viewWidth * 0.35} 365 ${viewWidth * 0.725} 480 Q${viewWidth} 415 ${viewWidth + 50} 465V480Z`}
              fill="#ccd8b7"
            />
            {world.branches.map((branch, index) => (
              <g key={index}>
                <LeafyBranch
                  x={branch.x}
                  end={branch.gapY - branch.gap / 2}
                  down
                  seed={branch.seed ?? index * 0.271}
                />
                <LeafyBranch
                  x={branch.x}
                  end={branch.gapY + branch.gap / 2}
                  down={false}
                  seed={branch.seed ?? index * 0.271}
                />
              </g>
            ))}
            <g
              transform={`translate(${FLIGHT.oliveX},${world.y}) rotate(${oliveAngle(world.velocity)})`}
            >
              <path
                d="M-13 0 Q-38 -18 -27 8 Q-23 16 -12 8"
                fill="#faf8e9"
                stroke="#899c72"
                strokeWidth="1.5"
              />
              <ellipse
                rx="15"
                ry={FLIGHT.radiusY}
                fill="url(#flight-olive)"
                stroke="#425431"
                strokeWidth="1.5"
              />
              <path
                d="M0 -18 Q-4 -28 4 -31 M0 -23 Q11 -36 16 -28 Q9 -20 0 -23"
                fill="#71854f"
                stroke="#556a3c"
                strokeWidth="1.5"
              />
              <ellipse
                cx="-5"
                cy="-5"
                rx="3"
                ry="6"
                fill="#dbe0af"
                opacity=".6"
              />
              <circle cx="7" cy="-3" r="2" fill="#25351f" />
            </g>
          </svg>
          {(phase !== "playing" || world.over) && (
            <span className="flight-overlay">
              <strong>
                {world.over
                  ? "A lovely little flight."
                  : phase === "paused"
                    ? "Catch your breath."
                    : "Meet your flying olive."}
              </strong>
              <span>
                {world.over
                  ? "Tap to try again"
                  : phase === "paused"
                    ? "Tap to resume"
                    : "Tap, click, or press Space to fly"}
              </span>
            </span>
          )}
        </button>
      </div>
      <p className="flight-instructions" id="flight-help">
        Tap to flap. Fly through the gaps between leafy branches. Space or Enter
        works when the game is focused.
      </p>
      <div className="game-bottom">
        <p role="status" aria-live="polite">
          {message}
        </p>
        <div className="flight-controls">
          <Button
            variant="quiet"
            disabled={phase === "ready" || world.over}
            onClick={() => {
              setPhase((current) =>
                current === "paused" ? "playing" : "paused",
              );
              if (phase === "paused") stage.current?.focus();
            }}
          >
            {phase === "paused" ? <Play size={16} /> : <Pause size={16} />}{" "}
            {phase === "paused" ? "Resume" : "Pause"}
          </Button>
          <Button
            variant="quiet"
            onClick={() => {
              setWorld(newFlight(widthRef.current, Math.random));
              setPhase("ready");
              stage.current?.focus();
            }}
          >
            <RotateCcw size={16} /> New round
          </Button>
        </div>
      </div>
    </section>
  );
}
