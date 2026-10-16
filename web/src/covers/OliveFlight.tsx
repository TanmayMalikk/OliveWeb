import { useEffect, useRef, useState } from "react";
import { Pause, Play, RotateCcw } from "lucide-react";
import { Button } from "../components";
import { readCover, writeCover } from "../services/cover-storage";
import {
  advanceFlight,
  branchPath,
  FLIGHT,
  leafPaths,
  newFlight,
  flightSpeedMultiplier,
  oliveAngle,
} from "./olive-flight";

function LeafyBranch({
  x,
  end,
  down,
}: {
  x: number;
  end: number;
  down: boolean;
}) {
  const height = down ? end : FLIGHT.height - end;
  return (
    <g transform={`translate(${x},${end}) scale(1,${down ? -1 : 1})`}>
      <path
        d={branchPath(height)}
        stroke="#746444"
        strokeWidth={FLIGHT.branchStroke}
        strokeLinecap="round"
        fill="none"
      />
      <path
        d={`M25 0 Q20 ${height * 0.36} 29 ${height}`}
        stroke="#aa9871"
        strokeWidth="3"
        strokeLinecap="round"
        fill="none"
      />
      {Array.from({ length: Math.floor(height / 33) }, (_, index) => {
        const y = 20 + index * 33;
        return (
          <g key={index} transform={`translate(26,${y})`}>
            <path d={leafPaths[0]} fill={index % 2 ? "#7e9260" : "#9ba978"} />
            <path d={leafPaths[1]} fill={index % 2 ? "#a8b58a" : "#728655"} />
            <path
              d="M0 3 L-19 23 M0 9 L19 28"
              stroke="#526640"
              strokeWidth="1"
              fill="none"
            />
          </g>
        );
      })}
    </g>
  );
}

export default function OliveFlight() {
  const [world, setWorld] = useState(() => newFlight());
  const [phase, setPhase] = useState<"ready" | "playing" | "paused">("ready");
  const [best, setBest] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  const stage = useRef<HTMLButtonElement>(null);
  const [viewWidth, setViewWidth] = useState<number>(FLIGHT.width);
  const widthRef = useRef<number>(FLIGHT.width);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
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
      if (phaseRef.current === "ready") setWorld(newFlight(width));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
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
  useEffect(() => {
    if (phase !== "playing" || world.over) return;
    let frame = 0,
      previous = 0;
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
  useEffect(() => {
    function hide() {
      if (document.hidden)
        setPhase((current) => (current === "playing" ? "paused" : current));
    }
    document.addEventListener("visibilitychange", hide);
    return () => document.removeEventListener("visibilitychange", hide);
  }, []);
  useEffect(() => {
    if (loaded && world.over && world.score > (best ?? 0)) {
      setBest(world.score);
      void writeCover("olive-flight-best", world.score).catch(() => {});
    }
  }, [world.over, world.score, best, loaded]);
  function flap() {
    if (world.over || phase === "ready") {
      setWorld({ ...newFlight(widthRef.current), velocity: FLIGHT.lift });
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
                />
                <LeafyBranch
                  x={branch.x}
                  end={branch.gapY + branch.gap / 2}
                  down={false}
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
              setWorld(newFlight(widthRef.current));
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
