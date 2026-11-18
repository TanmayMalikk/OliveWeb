// Canopy Breaker renders the classic paddle game with Olive Branch artwork.
import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Heart, Pause, Play, RotateCcw, Sparkles } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  BREAKER,
  advanceBreaker,
  launchBreakerBall,
  moveBreakerPaddle,
  newBreaker,
  type BreakerPowerKind,
  type BreakerWorld,
} from "./olive-breaker";

type BreakerPhase = "ready" | "playing" | "paused" | "over";

const BRICK_PALETTE: Record<string, { fill: string; top: string; stroke: string }> = {
  gold: { fill: "#dfb04f", top: "#f5d47e", stroke: "#8f6518" },
  cedar: { fill: "#c67746", top: "#df986a", stroke: "#7f4421" },
  olive: { fill: "#4a683a", top: "#688955", stroke: "#283d1e" },
  sage: { fill: "#82a066", top: "#a1be83", stroke: "#4c6638" },
  sprout: { fill: "#a4c66e", top: "#c2de8f", stroke: "#618037" },
};
const POWER_STYLE: Record<BreakerPowerKind, { label: string; color: string; name: string }> = {
  wide: { label: "WIDE", color: "#32633f", name: "Wide paddle" },
  slow: { label: "SLOW", color: "#306175", name: "Slow ball" },
  multiball: { label: "+1", color: "#884458", name: "Extra olive" },
};

type BallTrail = Map<boolean, Array<{ x: number; y: number }>>;

function drawBreakerCanvas(
  ctx: CanvasRenderingContext2D,
  world: BreakerWorld,
  trails: BallTrail,
  oliveSprite: HTMLImageElement | null,
) {
  const { width, height } = BREAKER;
  const sky = ctx.createLinearGradient(0, 0, 0, height);
  sky.addColorStop(0, "#e9f0dc");
  sky.addColorStop(0.75, "#f7f8ee");
  sky.addColorStop(1, "#e6edd8");
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, width, height);

  // Subtle orchard hills backdrop matching Olive Flight / Orchard palette.
  ctx.fillStyle = "#dce5cb";
  ctx.beginPath();
  ctx.moveTo(0, height - 38);
  ctx.quadraticCurveTo(width * 0.28, height - 86, width * 0.62, height - 42);
  ctx.quadraticCurveTo(width * 0.85, height - 22, width, height - 52);
  ctx.lineTo(width, height);
  ctx.lineTo(0, height);
  ctx.closePath();
  ctx.fill();

  // Arcade court wooden-grove boundary walls.
  ctx.strokeStyle = "#6f5334";
  ctx.lineWidth = 6;
  ctx.beginPath();
  ctx.moveTo(3, height);
  ctx.lineTo(3, 3);
  ctx.lineTo(width - 3, 3);
  ctx.lineTo(width - 3, height);
  ctx.stroke();

  // Leafy timber bricks with beveled highlights.
  for (const brick of world.bricks) {
    const pal = BRICK_PALETTE[brick.tone] ?? BRICK_PALETTE.sage;
    ctx.fillStyle = pal.fill;
    ctx.strokeStyle = pal.stroke;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.roundRect(brick.x, brick.y, brick.width, brick.height, 4.5);
    ctx.fill();
    ctx.stroke();

    // Sunlit bevel highlight.
    ctx.fillStyle = pal.top;
    ctx.beginPath();
    ctx.roundRect(brick.x + 2.5, brick.y + 2, brick.width - 5, 4.5, 2);
    ctx.fill();

    // Olive leaf emblem stamp on high-value bricks.
    if (brick.points >= 5) {
      ctx.save();
      ctx.translate(brick.x + 11, brick.y + brick.height / 2 + 1);
      ctx.rotate(-0.45);
      ctx.fillStyle = "rgba(255, 255, 245, 0.35)";
      ctx.beginPath();
      ctx.ellipse(0, 0, 5, 2.5, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

  }

  for (const drop of world.drops) {
    const { label, color } = POWER_STYLE[drop.kind];
    ctx.save();
    ctx.translate(drop.x, drop.y);
    ctx.fillStyle = color;
    ctx.strokeStyle = "#faf8e8";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.roundRect(-22, -9, 44, 18, 9);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#fff";
    ctx.font = "bold 11px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, 0, 0);
    ctx.restore();
  }

  // Orchard Log Paddle (matches Olive Crossing log timber asset + leafy tips).
  const px = world.paddleX - world.paddleWidth / 2;
  const py = BREAKER.paddleY;
  const ph = BREAKER.paddleHeight;

  // Soft drop shadow
  ctx.fillStyle = "rgba(32, 48, 28, 0.18)";
  ctx.beginPath();
  ctx.roundRect(px + 2, py + 4, world.paddleWidth, ph, 7);
  ctx.fill();

  // Main wooden log beam
  ctx.fillStyle = "#7f5431";
  ctx.strokeStyle = "#462c17";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.roundRect(px, py, world.paddleWidth, ph, 7);
  ctx.fill();
  ctx.stroke();

  // Sunlit bark top highlight
  ctx.fillStyle = "#a67549";
  ctx.beginPath();
  ctx.roundRect(px + 10, py + 2, world.paddleWidth - 20, 5, 2.5);
  ctx.fill();

  // Angled bronze bumper ends
  ctx.fillStyle = "#dfb04f";
  ctx.beginPath();
  ctx.roundRect(px + 2, py + 2, 10, ph - 4, 4);
  ctx.roundRect(px + world.paddleWidth - 12, py + 2, 10, ph - 4, 4);
  ctx.fill();

  // Center Olive Branch emblem on paddle
  if (oliveSprite && oliveSprite.complete && oliveSprite.naturalWidth > 0) {
    ctx.save();
    ctx.beginPath();
    ctx.arc(world.paddleX, py + ph / 2, 6, 0, Math.PI * 2);
    ctx.clip();
    ctx.drawImage(oliveSprite, world.paddleX - 6, py + ph / 2 - 6, 12, 12);
    ctx.restore();
  }

  // Aim guide when ball is resting on the paddle ready to serve.
  if (world.stuckToPaddle && !world.over && world.balls[0]) {
    const b = world.balls[0];
    ctx.save();
    ctx.strokeStyle = "rgba(67, 94, 53, 0.48)";
    ctx.lineWidth = 2;
    ctx.setLineDash([4, 5]);
    ctx.beginPath();
    ctx.moveTo(b.x, b.y);
    ctx.lineTo(b.x + (b.x < width / 2 ? 22 : -22), b.y - 68);
    ctx.stroke();
    ctx.restore();
  }

  // Ball velocity trails.
  for (const [bonus, trail] of trails) {
    for (let t = 0; t < trail.length; t++) {
      const pt = trail[t];
      const frac = (t + 1) / (trail.length + 1);
      ctx.globalAlpha = frac * 0.28;
      ctx.fillStyle = bonus ? "#aa8130" : "#6c8c4a";
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, BREAKER.ballRadius * frac * 0.85, 0, Math.PI * 2);
      ctx.fill();
    }
  }
  ctx.globalAlpha = 1;

  // Playable Olive Sprites as the Breaker balls (matching Olive Flight asset!).
  for (const ball of world.balls) {
    ctx.save();
    ctx.translate(ball.x, ball.y);

    const grad = ctx.createRadialGradient(-3, -3, 1.5, 0, 0, BREAKER.ballRadius);
    grad.addColorStop(0, ball.bonus ? "#f5d77d" : "#b8c77f");
    grad.addColorStop(1, ball.bonus ? "#80611f" : "#425932");
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(0, 0, BREAKER.ballRadius, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = "#2b3d20";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Olive highlight + pimento crown & twin leaf sprout.
    ctx.fillStyle = "rgba(255, 255, 238, 0.6)";
    ctx.beginPath();
    ctx.ellipse(-3, -3, 2, 3.5, -0.35, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = "#6fa046";
    ctx.strokeStyle = "#3f5927";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.ellipse(3, -BREAKER.ballRadius - 1, 4, 2.2, -0.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }
}

export default function OliveBreaker() {
  const worldRef = useRef<BreakerWorld>(newBreaker());
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const heldDirection = useRef<-1 | 0 | 1>(0);
  const trailsRef = useRef<BallTrail>(new Map());
  const pointerDownPosRef = useRef<{ x: number; moved: boolean } | null>(null);
  const oliveImgRef = useRef<HTMLImageElement | null>(null);

  const [phase, setPhase] = useState<BreakerPhase>("ready");
  const phaseRef = useRef<BreakerPhase>("ready");
  phaseRef.current = phase;
  const [hud, setHud] = useState({
    score: 0,
    wave: 1,
    lives: 3,
    stuck: true,
    bricksLeft: worldRef.current.bricks.length,
  });
  const [best, setBest] = useState(0);
  const [previousBest, setPreviousBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);
  const [powerNotice, setPowerNotice] = useState("");

  useEffect(() => {
    const oliveImg = new Image();
    oliveImg.src = "/icons/olive.svg";
    oliveImg.onload = () => {
      oliveImgRef.current = oliveImg;
    };
  }, []);

  useEffect(() => {
    let live = true;
    void readCover<number>("olive-breaker-arcade-best", 0)
      .then((value) => {
        if (live) {
          setBest(value);
          setPreviousBest(value);
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

  const syncHud = useCallback((w: BreakerWorld) => {
    setHud((prev) =>
      prev.score === w.score &&
      prev.wave === w.wave &&
      prev.lives === w.lives &&
      prev.stuck === w.stuckToPaddle &&
      prev.bricksLeft === w.bricks.length
        ? prev
        : {
            score: w.score,
            wave: w.wave,
            lives: w.lives,
            stuck: w.stuckToPaddle,
            bricksLeft: w.bricks.length,
          },
    );
  }, []);

  useEffect(() => {
    if (!storageReady || hud.score <= best) return;
    setBest(hud.score);
    void writeCover("olive-breaker-best", hud.score).catch(() => {});
  }, [best, hud.score, storageReady]);

  const paint = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const targetW = BREAKER.width * dpr;
    const targetH = BREAKER.height * dpr;
    if (canvas.width !== targetW || canvas.height !== targetH) {
      canvas.width = targetW;
      canvas.height = targetH;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    drawBreakerCanvas(
      ctx,
      worldRef.current,
      trailsRef.current,
      oliveImgRef.current,
    );
  }, []);

  useEffect(() => {
    paint();
  }, [paint, phase]);

  useEffect(() => {
    if (phase !== "playing") return;
    let raf = 0;
    let last = 0;
    const tick = (now: number) => {
      if (phaseRef.current !== "playing") return;
      const dt = last ? (now - last) / 1000 : 0.016;
      last = now;
      if (heldDirection.current !== 0) {
        worldRef.current = moveBreakerPaddle(
          worldRef.current,
          worldRef.current.paddleX + heldDirection.current * 460 * Math.min(dt, 0.032),
        );
      }
      const { world: next, events } = advanceBreaker(worldRef.current, dt);
      worldRef.current = next;
      if (next.stuckToPaddle) {
        trailsRef.current.clear();
      } else {
        trailsRef.current = new Map(next.balls.map((ball) => {
          const bonus = Boolean(ball.bonus);
          const prevTrail = trailsRef.current.get(bonus) ?? [];
          return [bonus, [...prevTrail, { x: ball.x, y: ball.y }].slice(-7)];
        }));
      }
      if (events.bouncedPaddle) gameSfx.bounce();
      if (events.hitBrick) gameSfx.tap();
      if (events.caught.length) {
        gameSfx.powerup();
        setPowerNotice(events.caught.map((power) =>
          power === "multiball" ? "Extra olive launched." : `${POWER_STYLE[power].name} caught.`,
        ).join(" "));
      } else if (events.lostLife || events.clearedWave) {
        setPowerNotice("");
      }
      if (events.clearedWave) gameSfx.win();
      if (events.lostLife) gameSfx.miss();
      paint();
      syncHud(next);
      if (next.over) {
        phaseRef.current = "over";
        setPhase("over");
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [phase, paint, syncHud]);

  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden && phaseRef.current === "playing") setPhase("paused");
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, []);

  const startRound = useCallback(() => {
    setPowerNotice("");
    setPreviousBest(best);
    trailsRef.current.clear();
    const fresh = newBreaker();
    worldRef.current = fresh;
    phaseRef.current = "playing";
    setPhase("playing");
    syncHud(fresh);
    paint();
    gameSfx.tap();
    requestAnimationFrame(() => stageRef.current?.focus());
  }, [best, paint, syncHud]);

  const serveBall = useCallback(() => {
    if (!worldRef.current.stuckToPaddle || phaseRef.current !== "playing") return;
    const next = launchBreakerBall(worldRef.current);
    worldRef.current = next;
    syncHud(next);
    paint();
    gameSfx.flap();
  }, [paint, syncHud]);

  const moveToPointer = useCallback(
    (clientX: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const rect = canvas.getBoundingClientRect();
      if (!rect.width) return;
      const worldX = ((clientX - rect.left) / rect.width) * BREAKER.width;
      const next = moveBreakerPaddle(worldRef.current, worldX);
      worldRef.current = next;
      syncHud(next);
      paint();
    },
    [paint, syncHud],
  );

  const maxHearts = Math.max(3, hud.lives);

  return (
    <section className="grove-game arcade-game" aria-label="Canopy Breaker game">
      <div className="game-score">
        <span>
          SCORE <strong aria-live="polite">{hud.score}</strong>
        </span>
        <span>
          LEVEL <strong>{hud.wave}</strong>
        </span>
        <span>
          LIVES{" "}
          <strong>
            {Array.from({ length: maxHearts }, (_, i) => (
              <Heart
                key={i}
                size={15}
                className={i < hud.lives ? "heart-active" : "heart-empty"}
                fill={i < hud.lives ? "currentColor" : "none"}
                aria-hidden="true"
              />
            ))}
            <span className="sr-only">{hud.lives}</span>
          </strong>
        </span>
        <span>
          YOUR BEST <strong>{best || "—"}</strong>
        </span>
      </div>

      <div className="canvas-arena">
        <div
          ref={stageRef}
          className="canvas-stage"
          role="group"
          aria-label="Canopy Breaker board. Slide finger or use Left and Right arrows to steer, Space or tap to launch."
          tabIndex={0}
          onKeyDown={(event) => {
            if (event.key === "ArrowLeft" || event.key === "a" || event.key === "A") {
              event.preventDefault();
              heldDirection.current = -1;
            } else if (event.key === "ArrowRight" || event.key === "d" || event.key === "D") {
              event.preventDefault();
              heldDirection.current = 1;
            } else if (event.key === " " || event.key === "Enter") {
              event.preventDefault();
              if (phaseRef.current === "ready" || phaseRef.current === "over") startRound();
              else if (worldRef.current.stuckToPaddle) serveBall();
            }
          }}
          onKeyUp={(event) => {
            if (["ArrowLeft", "a", "A", "ArrowRight", "d", "D"].includes(event.key)) {
              heldDirection.current = 0;
            }
          }}
        >
          <canvas
            ref={canvasRef}
            width={BREAKER.width}
            height={BREAKER.height}
            onPointerDown={(event) => {
              if (phaseRef.current !== "playing") return;
              event.currentTarget.setPointerCapture(event.pointerId);
              pointerDownPosRef.current = { x: event.clientX, moved: false };
              moveToPointer(event.clientX);
            }}
            onPointerMove={(event) => {
              if (phaseRef.current !== "playing") return;
              if (pointerDownPosRef.current && Math.abs(event.clientX - pointerDownPosRef.current.x) > 8) {
                pointerDownPosRef.current.moved = true;
              }
              if (event.buttons > 0 || event.pointerType === "mouse") {
                moveToPointer(event.clientX);
              }
            }}
            onPointerUp={() => {
              if (phaseRef.current !== "playing") return;
              if (worldRef.current.stuckToPaddle && !pointerDownPosRef.current?.moved) {
                serveBall();
              }
              pointerDownPosRef.current = null;
            }}
          />
          {phase === "ready" && (
            <GameOverlay
              tone="ready"
              icon={<Sparkles size={22} />}
              title="Canopy Breaker"
              subtitle="Clear both walls. Catch falling capsules with the paddle to activate power-ups."
              best={best || null}
              controlsHint="Move the paddle, then tap the board or press Space to serve"
              actionLabel="Start game"
              actionAriaLabel="Start Canopy Breaker"
              onAction={startRound}
            />
          )}
          {phase === "paused" && (
            <GameOverlay
              tone="paused"
              title="Paused"
              subtitle="Take a breath. Your level and score stay right where you left them."
              score={hud.score}
              best={best || null}
              actionLabel="Resume"
              onAction={() => setPhase("playing")}
              secondaryLabel="New round"
              onSecondary={startRound}
            />
          )}
          {phase === "over" && (
            <GameOverlay
              tone={worldRef.current.won ? "won" : "over"}
              title={worldRef.current.won ? "Both walls cleared!" : "All leaves settled"}
              subtitle={`You reached level ${hud.wave} with ${hud.score} points.`}
              score={hud.score}
              best={best}
              isNewBest={hud.score > 0 && hud.score > previousBest}
              actionLabel="Play again"
              actionAriaLabel="Play Canopy Breaker again"
              onAction={startRound}
            />
          )}
        </div>
      </div>

      <div className="game-bottom touch-game-bottom">
        <p role="status" aria-live="polite">
          {phase === "ready"
            ? "Slide your paddle and launch the olive."
            : phase === "paused"
              ? "Paused. Take your time."
              : phase === "over"
                ? `Round over. Final score ${hud.score}.`
                : hud.stuck
                  ? "Position paddle and tap board, Space, or Launch."
                  : `${powerNotice ? powerNotice + " " : ""}Level ${hud.wave} · ${hud.bricksLeft} bricks left.`}
        </p>
        <div className="touch-arcade-controls">
          <div className="touch-steer-pad" role="group" aria-label="Paddle controls">
            <button
              type="button"
              aria-label="Move paddle left"
              onPointerDown={(e) => {
                e.preventDefault();
                heldDirection.current = -1;
              }}
              onPointerUp={() => (heldDirection.current = 0)}
              onPointerCancel={() => (heldDirection.current = 0)}
              onPointerLeave={() => (heldDirection.current = 0)}
              onClick={() => {
                worldRef.current = moveBreakerPaddle(
                  worldRef.current,
                  worldRef.current.paddleX - 36,
                );
                paint();
              }}
            >
              <ArrowLeft size={20} aria-hidden="true" /> Left
            </button>
            {hud.stuck && phase === "playing" && (
              <Button variant="outline" onClick={serveBall}>
                Launch
              </Button>
            )}
            <button
              type="button"
              aria-label="Move paddle right"
              onPointerDown={(e) => {
                e.preventDefault();
                heldDirection.current = 1;
              }}
              onPointerUp={() => (heldDirection.current = 0)}
              onPointerCancel={() => (heldDirection.current = 0)}
              onPointerLeave={() => (heldDirection.current = 0)}
              onClick={() => {
                worldRef.current = moveBreakerPaddle(
                  worldRef.current,
                  worldRef.current.paddleX + 36,
                );
                paint();
              }}
            >
              Right <ArrowRight size={20} aria-hidden="true" />
            </button>
          </div>
          <div className="flight-controls">
            <Button
              variant="quiet"
              disabled={phase === "ready" || phase === "over"}
              onClick={() =>
                setPhase((curr) => (curr === "paused" ? "playing" : "paused"))
              }
            >
              {phase === "paused" ? <Play size={16} /> : <Pause size={16} />}{" "}
              {phase === "paused" ? "Resume" : "Pause"}
            </Button>
            <Button variant="quiet" onClick={startRound}>
              <RotateCcw size={16} /> New round
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
