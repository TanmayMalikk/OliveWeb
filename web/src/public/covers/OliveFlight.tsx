// Cover users: 1:1 Flappy Bird pacing with the original Olive Flight SVG sky, leafy branches, Olive sprite, and zero-flicker 60fps loop.
import { memo, useCallback, useEffect, useRef, useState } from "react";
import { Award, Pause, Play, RotateCcw, Sprout, Wind } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  advanceFlight,
  branchPath,
  FLIGHT,
  leafPaths,
  newFlight,
  flightSpeedMultiplier,
  oliveAngle,
  type Branch,
  type FlightWorld,
} from "./olive-flight";

interface FeatherParticle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  size: number;
  life: number;
  maxLife: number;
  rotation: number;
  color: string;
}

function medalForScore(score: number) {
  if (score >= 50) return { label: "Platinum Olive", color: "#dfe8ec", border: "#667e8c" };
  if (score >= 35) return { label: "Gold Olive", color: "#f6d354", border: "#9a6f0f" };
  if (score >= 20) return { label: "Silver Olive", color: "#dce2e6", border: "#6c7780" };
  if (score >= 10) return { label: "Bronze Olive", color: "#dfa167", border: "#844e1d" };
  return null;
}

// Original LeafyBranch SVG asset — stably keyed by branch.id so visible branches never remount or flicker mid-flight.
const LeafyBranch = memo(function LeafyBranch({
  end,
  down,
  seed,
}: {
  end: number;
  down: boolean;
  seed: number;
}) {
  const height = Math.max(24, down ? end : FLIGHT.height - end);
  const variation = seed + (down ? 0.173 : 0.719);
  const random = (index: number, salt = 0) => {
    const value =
      Math.sin(variation * 8191 + index * 127.1 + salt * 311.7) * 43758.5453;
    return value - Math.floor(value);
  };
  const sway = (random(0, 1) - 0.5) * 9;
  const branchShape = `M27 0 Q${10 + sway} ${height * 0.36} ${23 + sway * 0.25} ${height} L${35 + sway * 0.25} ${height} Q${24 + sway} ${height * 0.36} 27 0Z`;
  const leafCount = Math.max(1, Math.floor((height - 10) / 32));
  return (
    <g transform={`translate(0,${end}) scale(1,${down ? -1 : 1})`}>
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
});

export default function OliveFlight() {
  const initial = useRef<FlightWorld | null>(null);
  if (!initial.current) initial.current = newFlight(FLIGHT.width, Math.random);

  const worldRef = useRef<FlightWorld>(initial.current);
  const [branchSlots, setBranchSlots] = useState<Branch[]>(
    () => initial.current!.branches,
  );
  const [phase, setPhase] = useState<"ready" | "playing" | "paused">("ready");
  const [score, setScore] = useState(0);
  const [over, setOver] = useState(false);
  const [best, setBest] = useState<number | null>(null);
  const [previousBest, setPreviousBest] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [viewWidth, setViewWidth] = useState<number>(FLIGHT.width);

  const stage = useRef<HTMLButtonElement>(null);
  const fxCanvasRef = useRef<HTMLCanvasElement>(null);
  const oliveGroupRef = useRef<SVGGElement>(null);
  const wingPathRef = useRef<SVGPathElement>(null);
  const branchGroupRefs = useRef<Map<number, SVGGElement>>(new Map());
  const particlesRef = useRef<FeatherParticle[]>([]);

  const widthRef = useRef<number>(FLIGHT.width);
  const canvasPxRef = useRef({ w: 0, h: 0, dpr: 1 });
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  const overRef = useRef(over);
  overRef.current = over;
  const wingPhaseRef = useRef(0);
  const idleTimeRef = useRef(0);

  const spawnFlapBurst = useCallback((y: number, count = 6, gold = false) => {
    const colors = gold
      ? ["#f4c856", "#88a46c", "#fbf8e9"]
      : ["#fbf8e9", "#ccd8b7", "#91a36d"];
    for (let i = 0; i < count; i++) {
      particlesRef.current.push({
        x: FLIGHT.oliveX - 10 + (Math.random() - 0.5) * 10,
        y: y + (Math.random() - 0.5) * 12,
        vx: -70 - Math.random() * 85,
        vy: 15 + (Math.random() - 0.5) * 70,
        size: 3.5 + Math.random() * 4,
        life: 0.45 + Math.random() * 0.25,
        maxLife: 0.65,
        rotation: Math.random() * Math.PI,
        color: colors[i % colors.length],
      });
    }
    if (particlesRef.current.length > 48) {
      particlesRef.current = particlesRef.current.slice(-48);
    }
  }, []);

  const drawFx = useCallback((dt: number, clear = false) => {
    const canvas = fxCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const w = Math.max(1, widthRef.current);
    const h = FLIGHT.height;
    const scaleX = canvas.width / w;
    const scaleY = canvas.height / h;
    ctx.setTransform(scaleX || 1, 0, 0, scaleY || 1, 0, 0);
    ctx.clearRect(0, 0, w, h);
    if (clear) return;

    const alive: FeatherParticle[] = [];
    for (const p of particlesRef.current) {
      const nextLife = p.life - dt;
      if (nextLife <= 0) continue;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.rotation += dt * 3;
      p.life = nextLife;
      alive.push(p);
      ctx.save();
      ctx.globalAlpha = Math.max(0, p.life / p.maxLife) * 0.85;
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rotation);
      ctx.fillStyle = p.color;
      ctx.beginPath();
      ctx.ellipse(0, 0, p.size, p.size * 0.55, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
    particlesRef.current = alive;
  }, []);

  // Sync stage viewBox & particle canvas pixel backing strictly inside ResizeObserver (never per-frame)
  useEffect(() => {
    const element = stage.current;
    const canvas = fxCanvasRef.current;
    if (!element) return;

    const syncDimensions = () => {
      if (!element.clientHeight || !element.clientWidth) return;
      const worldW = (element.clientWidth / element.clientHeight) * FLIGHT.height;
      if (canvas) {
        const dpr = Math.min(2, window.devicePixelRatio || 1);
        const pxW = Math.max(1, Math.round(element.clientWidth * dpr));
        const pxH = Math.max(1, Math.round(element.clientHeight * dpr));
        if (
          canvasPxRef.current.w !== pxW ||
          canvasPxRef.current.h !== pxH ||
          canvasPxRef.current.dpr !== dpr
        ) {
          canvas.width = pxW;
          canvas.height = pxH;
          canvasPxRef.current = { w: pxW, h: pxH, dpr };
        }
      }
      if (Math.abs(widthRef.current - worldW) < 0.5) return;
      widthRef.current = worldW;
      setViewWidth(worldW);
      if (phaseRef.current === "ready") {
        const fresh = newFlight(worldW, Math.random);
        worldRef.current = fresh;
        setBranchSlots(fresh.branches);
      }
    };

    syncDimensions();
    const observer = new ResizeObserver(syncDimensions);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let live = true;
    void readCover<number | null>("olive-flight-best", null)
      .then((value) => {
        if (live) {
          setBest(value);
          setPreviousBest(value);
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

  // Zero-flicker 60fps animation loop: ready bobbing + smooth flight via direct SVG DOM transforms.
  useEffect(() => {
    if (phase === "paused" || over) return;
    let frame = 0;
    let previous = 0;

    function tick(time: number) {
      const elapsed = previous ? Math.min(0.05, (time - previous) / 1000) : 0;
      previous = time;

      if (phaseRef.current === "ready") {
        idleTimeRef.current += elapsed;
        const bobY = 230 + Math.sin(idleTimeRef.current * 4.2) * 5;
        const flapDeg = Math.sin(idleTimeRef.current * 10) * 14;
        oliveGroupRef.current?.setAttribute(
          "transform",
          `translate(${FLIGHT.oliveX},${bobY}) rotate(0)`,
        );
        wingPathRef.current?.setAttribute("transform", `rotate(${flapDeg} -13 2)`);
        frame = requestAnimationFrame(tick);
        return;
      }

      if (phaseRef.current !== "playing") return;

      if (elapsed > 0) {
        const prev = worldRef.current;
        const next = advanceFlight(prev, elapsed, Math.random, widthRef.current);
        worldRef.current = next;

        wingPhaseRef.current += elapsed * (next.velocity < 0 ? 22 : 8);
        const wingDeg =
          next.velocity < 0 ? Math.sin(wingPhaseRef.current) * 24 : 8;

        oliveGroupRef.current?.setAttribute(
          "transform",
          `translate(${FLIGHT.oliveX},${next.y}) rotate(${oliveAngle(next.velocity)})`,
        );
        wingPathRef.current?.setAttribute("transform", `rotate(${wingDeg} -13 2)`);

        for (let i = 0; i < next.branches.length; i++) {
          const b = next.branches[i];
          const key = b.id ?? i;
          branchGroupRefs.current
            .get(key)
            ?.setAttribute("transform", `translate(${b.x},0)`);
        }

        if (next.score > prev.score) {
          setScore(next.score);
          gameSfx.score();
          spawnFlapBurst(next.y, 8, true);
        }

        drawFx(Math.min(elapsed, 0.05));

        // Update React branch slots ONLY when a branch enters/exits; visible branches keep their exact key & never flash.
        const slotsChanged =
          next.branches.length !== prev.branches.length ||
          next.branches.some((b, i) => b.id !== prev.branches[i]?.id);
        if (slotsChanged) {
          setBranchSlots(next.branches);
        }

        if (next.over && !prev.over) {
          overRef.current = true;
          setOver(true);
          setScore(next.score);
          gameSfx.miss();
          return;
        }
      }
      frame = requestAnimationFrame(tick);
    }

    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [phase, over, drawFx, spawnFlapBurst]);

  useEffect(() => {
    function hide() {
      if (document.hidden && phaseRef.current === "playing") {
        phaseRef.current = "paused";
        setPhase("paused");
      }
    }
    document.addEventListener("visibilitychange", hide);
    return () => document.removeEventListener("visibilitychange", hide);
  }, []);

  useEffect(() => {
    if (loaded && over && score > (best ?? 0)) {
      setBest(score);
      void writeCover("olive-flight-best", score).catch(() => {});
    }
  }, [over, score, best, loaded]);

  function flap() {
    if (worldRef.current.over || phaseRef.current === "ready") {
      setPreviousBest(best);
      particlesRef.current = [];
      const fresh: FlightWorld = {
        ...newFlight(widthRef.current, Math.random),
        velocity: FLIGHT.lift,
      };
      worldRef.current = fresh;
      overRef.current = false;
      phaseRef.current = "playing";
      setBranchSlots(fresh.branches);
      setScore(0);
      setOver(false);
      setPhase("playing");
      oliveGroupRef.current?.setAttribute(
        "transform",
        `translate(${FLIGHT.oliveX},${fresh.y}) rotate(${oliveAngle(fresh.velocity)})`,
      );
      spawnFlapBurst(fresh.y, 7);
      gameSfx.flap();
    } else {
      const lifted: FlightWorld = {
        ...worldRef.current,
        velocity: FLIGHT.lift,
      };
      worldRef.current = lifted;
      if (phaseRef.current === "paused") {
        phaseRef.current = "playing";
        setPhase("playing");
      }
      spawnFlapBurst(lifted.y, 5);
      gameSfx.flap();
    }
  }

  const resetRound = () => {
    particlesRef.current = [];
    drawFx(0, true);
    const fresh = newFlight(widthRef.current, Math.random);
    worldRef.current = fresh;
    overRef.current = false;
    phaseRef.current = "ready";
    setBranchSlots(fresh.branches);
    setScore(0);
    setOver(false);
    setPhase("ready");
    oliveGroupRef.current?.setAttribute(
      "transform",
      `translate(${FLIGHT.oliveX},${fresh.y}) rotate(0)`,
    );
    stage.current?.focus();
  };

  const medal = over ? medalForScore(score) : null;
  const isNewRecord = over && score > 0 && score > (previousBest ?? 0);

  const message = over
    ? `Round over. You passed ${score} ${score === 1 ? "branch" : "branches"}. Try again?`
    : phase === "paused"
      ? "Paused. Take your time."
      : phase === "ready"
        ? "Ready when you are. Tap to take flight."
        : `${score} ${score === 1 ? "branch" : "branches"} passed.`;

  return (
    <section className="grove-game flight-game" aria-label="Olive Flight game">
      <div className="game-score">
        <span>
          BRANCHES <strong>{score}</strong>
        </span>
        <span>
          YOUR BEST <strong>{best ?? "—"}</strong>
        </span>
        <span>
          SPEED <strong>{flightSpeedMultiplier(score).toFixed(1)}×</strong>
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
            over
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
            {branchSlots.map((slot, index) => {
              const key = slot.id ?? index;
              const liveBranch =
                worldRef.current.branches.find((b) => (b.id ?? -1) === slot.id) ??
                slot;
              return (
                <g
                  key={key}
                  ref={(el) => {
                    if (el) branchGroupRefs.current.set(key, el);
                    else branchGroupRefs.current.delete(key);
                  }}
                  transform={`translate(${liveBranch.x},0)`}
                >
                  <LeafyBranch
                    end={liveBranch.gapY - liveBranch.gap / 2}
                    down
                    seed={liveBranch.seed ?? index * 0.271}
                  />
                  <LeafyBranch
                    end={liveBranch.gapY + liveBranch.gap / 2}
                    down={false}
                    seed={liveBranch.seed ?? index * 0.271}
                  />
                </g>
              );
            })}
            {phase === "playing" && !over && (
              <text
                x={viewWidth / 2}
                y="46"
                textAnchor="middle"
                fill="#ffffff"
                stroke="#324627"
                strokeWidth="4.5"
                paintOrder="stroke"
                fontFamily="system-ui, sans-serif"
                fontWeight="800"
                fontSize="34"
              >
                {score}
              </text>
            )}
            <g
              ref={oliveGroupRef}
              transform={`translate(${FLIGHT.oliveX},${worldRef.current.y}) rotate(${oliveAngle(worldRef.current.velocity)})`}
            >
              <path
                ref={wingPathRef}
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
          <canvas
            ref={fxCanvasRef}
            className="flight-fx-canvas"
            aria-hidden="true"
          />
          {(phase !== "playing" || over) && (
            <GameOverlay
              asButtonSurface
              tone={over ? "over" : phase === "paused" ? "paused" : "ready"}
              icon={over ? undefined : <Sprout size={22} />}
              title={
                over
                  ? "A lovely little flight"
                  : phase === "paused"
                    ? "Catch your breath"
                    : "Olive Flight"
              }
              subtitle={
                over
                  ? "Tap anywhere on the sky or use Flap below to try again."
                  : phase === "paused"
                    ? "Tap the stage or press Resume when you are ready."
                    : "Flutter gently between leafy branches at your own rhythm."
              }
              score={over || phase === "paused" ? score : undefined}
              scoreLabel="Branches"
              best={best}
              isNewBest={isNewRecord}
              controlsHint="Tap anywhere, tap the thumb Flap pad below, or press Space"
            >
              {medal ? (
                <div
                  className="flight-medal-pill"
                  style={{
                    background: medal.color,
                    borderColor: medal.border,
                  }}
                >
                  <Award size={14} aria-hidden="true" /> {medal.label}
                </div>
              ) : null}
            </GameOverlay>
          )}
        </button>
      </div>
      <p className="flight-instructions" id="flight-help">
        Tap the sky or the Flap button below to flutter. Space or Enter works
        when the game is focused.
      </p>
      <div className="game-bottom">
        <p role="status" aria-live="polite">
          {message}
        </p>
        <div className="flight-controls">
          <button
            type="button"
            className="button flight-flap-btn"
            onPointerDown={(event) => {
              if (event.button === 0) {
                event.preventDefault();
                flap();
              }
            }}
            onClick={(event) => {
              if (event.detail === 0) flap();
            }}
          >
            <Wind size={16} aria-hidden="true" />{" "}
            {over
              ? "Play again"
              : phase === "ready"
                ? "Take flight"
                : phase === "paused"
                  ? "Flap & resume"
                  : "Flap"}
          </button>
          <Button
            variant="quiet"
            disabled={phase === "ready" || over}
            onClick={() => {
              const next = phase === "paused" ? "playing" : "paused";
              phaseRef.current = next;
              setPhase(next);
              if (next === "playing") stage.current?.focus();
            }}
          >
            {phase === "paused" ? <Play size={16} /> : <Pause size={16} />}{" "}
            {phase === "paused" ? "Resume" : "Pause"}
          </Button>
          <Button variant="quiet" onClick={resetRound}>
            <RotateCcw size={16} /> New round
          </Button>
        </div>
      </div>
    </section>
  );
}
