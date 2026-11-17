// Cover users: 1:1 Brick Breaker gameplay with Olive Branch assets (Olive ball sprite, orchard log paddle, leafy bricks & capsules).
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

function drawBreakerCanvas(
  ctx: CanvasRenderingContext2D,
  world: BreakerWorld,
  trails: Array<{ x: number; y: number }[]>,
  oliveSprite: HTMLImageElement | null,
  sproutSprite: HTMLImageElement | null,
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

  // Leafy timber bricks with beveled highlights & visible damage cracks.
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

    // Olive leaf emblem stamp on gold/olive armored bricks.
    if (brick.maxHp > 1 || brick.tone === "gold") {
      ctx.save();
      ctx.translate(brick.x + 11, brick.y + brick.height / 2 + 1);
      ctx.rotate(-0.45);
      ctx.fillStyle = "rgba(255, 255, 245, 0.35)";
      ctx.beginPath();
      ctx.ellipse(0, 0, 5, 2.5, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    // Cracks for multi-hit armored bricks.
    if (brick.hp < brick.maxHp) {
      ctx.strokeStyle = "rgba(28, 34, 24, 0.65)";
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      ctx.moveTo(brick.x + brick.width * 0.34, brick.y + 3);
      ctx.lineTo(brick.x + brick.width * 0.46, brick.y + brick.height * 0.55);
      ctx.lineTo(brick.x + brick.width * 0.39, brick.y + brick.height - 3);
      if (brick.maxHp - brick.hp >= 2) {
        ctx.moveTo(brick.x + brick.width * 0.68, brick.y + 4);
        ctx.lineTo(brick.x + brick.width * 0.58, brick.y + brick.height * 0.62);
        ctx.lineTo(brick.x + brick.width * 0.72, brick.y + brick.height - 2);
      }
      ctx.stroke();
    }

    if (brick.hp > 1) {
      ctx.fillStyle = "#fdfdf5";
      ctx.font = "700 10px system-ui, sans-serif";
      ctx.textAlign = "right";
      ctx.textBaseline = "middle";
      ctx.fillText(
        `×${brick.hp}`,
        brick.x + brick.width - 6,
        brick.y + brick.height / 2 + 1,
      );
    }
  }

  // Falling Brick Breaker power-up tokens using our Olive Branch palette & emblems.
  for (const power of world.powers) {
    ctx.save();
    ctx.translate(power.x, power.y);
    const fill =
      power.kind === "wide"
        ? "#356642"
        : power.kind === "twin"
          ? "#a9672e"
          : power.kind === "fire"
            ? "#c2442e"
            : power.kind === "life"
              ? "#8c3549"
              : "#3d6d7c";
    ctx.fillStyle = fill;
    ctx.beginPath();
    ctx.roundRect(-20, -10, 40, 20, 6);
    ctx.fill();
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    if (sproutSprite && sproutSprite.complete && sproutSprite.naturalWidth > 0) {
      ctx.save();
      ctx.beginPath();
      ctx.arc(-11, 0, 6.5, 0, Math.PI * 2);
      ctx.clip();
      ctx.drawImage(sproutSprite, -17.5, -6.5, 13, 13);
      ctx.restore();
    }

    ctx.fillStyle = "#ffffff";
    ctx.font = "700 9.5px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    const label =
      power.kind === "wide"
        ? "WIDE"
        : power.kind === "twin"
          ? "×3"
          : power.kind === "fire"
            ? "FIRE"
            : power.kind === "life"
              ? "+1 UP"
              : "SLOW";
    ctx.fillText(label, 4, 0.5);
    ctx.restore();
  }

  // Brick shatter debris.
  for (const p of world.particles) {
    ctx.globalAlpha = Math.max(0, p.life / p.maxLife);
    ctx.fillStyle = p.color;
    ctx.fillRect(p.x - p.size * 0.4, p.y - p.size * 0.4, p.size * 0.8, p.size * 0.8);
  }
  ctx.globalAlpha = 1;

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
  ctx.fillStyle = world.wideTimer > 0 ? "#4f723b" : "#7f5431";
  ctx.strokeStyle = "#462c17";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.roundRect(px, py, world.paddleWidth, ph, 7);
  ctx.fill();
  ctx.stroke();

  // Sunlit bark top highlight
  ctx.fillStyle = world.wideTimer > 0 ? "#77a15a" : "#a67549";
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
    ctx.lineTo(b.x + 22, b.y - 68);
    ctx.stroke();
    ctx.restore();
  }

  // Ball velocity trails.
  const isFire = world.fireTimer > 0;
  for (let bIdx = 0; bIdx < trails.length; bIdx++) {
    const trail = trails[bIdx];
    for (let t = 0; t < trail.length; t++) {
      const pt = trail[t];
      const frac = (t + 1) / (trail.length + 1);
      ctx.globalAlpha = frac * (isFire ? 0.42 : 0.28);
      ctx.fillStyle = isFire ? "#e67b35" : "#6c8c4a";
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

    if (isFire) {
      ctx.fillStyle = "rgba(235, 118, 45, 0.35)";
      ctx.beginPath();
      ctx.arc(0, 0, BREAKER.ballRadius + 4, 0, Math.PI * 2);
      ctx.fill();
    }

    const grad = ctx.createRadialGradient(-3, -3, 1.5, 0, 0, BREAKER.ballRadius);
    grad.addColorStop(0, isFire ? "#ffe27a" : "#b8c77f");
    grad.addColorStop(1, isFire ? "#c74e24" : "#425932");
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(0, 0, BREAKER.ballRadius, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = isFire ? "#7a2611" : "#2b3d20";
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
  const trailsRef = useRef<Array<{ x: number; y: number }[]>>([]);
  const pointerDownPosRef = useRef<{ x: number; moved: boolean } | null>(null);
  const oliveImgRef = useRef<HTMLImageElement | null>(null);
  const sproutImgRef = useRef<HTMLImageElement | null>(null);

  const [phase, setPhase] = useState<BreakerPhase>("ready");
  const phaseRef = useRef<BreakerPhase>("ready");
  phaseRef.current = phase;
  const [hud, setHud] = useState({
    score: 0,
    wave: 1,
    lives: 3,
    combo: 0,
    stuck: true,
    bricksLeft: worldRef.current.bricks.length,
  });
  const [best, setBest] = useState(0);
  const [previousBest, setPreviousBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);

  useEffect(() => {
    const oliveImg = new Image();
    oliveImg.src = "/icons/olive.svg";
    oliveImg.onload = () => {
      oliveImgRef.current = oliveImg;
    };
    const sproutImg = new Image();
    sproutImg.src = "/icons/game.svg";
    sproutImg.onload = () => {
      sproutImgRef.current = sproutImg;
    };
  }, []);

  useEffect(() => {
    let live = true;
    void readCover<number>("olive-breaker-best", 0)
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
      prev.combo === w.combo &&
      prev.stuck === w.stuckToPaddle &&
      prev.bricksLeft === w.bricks.length
        ? prev
        : {
            score: w.score,
            wave: w.wave,
            lives: w.lives,
            combo: w.combo,
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
      sproutImgRef.current,
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
        trailsRef.current = [];
      } else {
        trailsRef.current = next.balls.map((ball, index) => {
          const prevTrail = trailsRef.current[index] ?? [];
          return [...prevTrail, { x: ball.x, y: ball.y }].slice(-7);
        });
      }
      if (events.bouncedPaddle) gameSfx.bounce();
      if (events.hitBrick) gameSfx.tap();
      if (events.powerUp) gameSfx.powerup();
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
    setPreviousBest(best);
    trailsRef.current = [];
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
    const offset = ((worldRef.current.paddleX / BREAKER.width) - 0.5) * 0.45 + 0.18;
    const next = launchBreakerBall(worldRef.current, offset);
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
              subtitle="1:1 classic brick breaker: bounce the olive off your orchard log paddle, break every row, and catch power capsules."
              best={best || null}
              controlsHint="Slide paddle to aim, then tap board, press Space, or tap Launch"
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
              tone="over"
              title="All leaves settled"
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
                  : hud.combo >= 3
                    ? `Streak ${hud.combo}×! ${hud.bricksLeft} bricks left.`
                    : `Level ${hud.wave} · ${hud.bricksLeft} bricks left.`}
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
