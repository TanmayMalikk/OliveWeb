// Cover users: interactive 60fps Canvas Orchard Catch game (touch-drag + buttons + keyboard).
import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Heart, Leaf, Pause, Play, RotateCcw } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  ORCHARD,
  advanceOrchard,
  moveOrchardBasket,
  newOrchard,
  type OrchardWorld,
} from "./orchard-catch";

type OrchardPhase = "ready" | "playing" | "paused" | "over";

function drawOrchardCanvas(
  ctx: CanvasRenderingContext2D,
  world: OrchardWorld,
  basketTilt: number,
) {
  const { width, height } = ORCHARD;
  const grad = ctx.createLinearGradient(0, 0, 0, height);
  grad.addColorStop(0, "#e9f0dc");
  grad.addColorStop(0.75, "#f9f8ec");
  grad.addColorStop(1, "#dce6c9");
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, width, height);

  // Crisp geometric wall guidelines for arcade boundaries
  ctx.strokeStyle = "#cedbc8";
  ctx.lineWidth = 4;
  ctx.strokeRect(2, 2, width - 4, height - 4);

  // Rolling orchard hills.
  ctx.fillStyle = "#cfdcb8";
  ctx.beginPath();
  ctx.moveTo(0, height - 48);
  ctx.quadraticCurveTo(width * 0.35, height - 92, width * 0.72, height - 52);
  ctx.quadraticCurveTo(width * 0.88, height - 38, width, height - 56);
  ctx.lineTo(width, height);
  ctx.lineTo(0, height);
  ctx.closePath();
  ctx.fill();

  // Falling geometric orchard items.
  for (const item of world.items) {
    ctx.save();
    ctx.translate(item.x, item.y);
    ctx.rotate(item.rotation * 0.35);
    if (item.kind === "pinecone") {
      ctx.fillStyle = "#7a5338";
      ctx.beginPath();
      ctx.ellipse(0, 0, 12, 16, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = "#4d3221";
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.strokeStyle = "#9c7050";
      ctx.beginPath();
      ctx.moveTo(-6, -4);
      ctx.lineTo(6, -4);
      ctx.moveTo(-8, 3);
      ctx.lineTo(8, 3);
      ctx.stroke();
    } else if (item.kind === "blossom") {
      ctx.fillStyle = "#f4c856";
      for (let i = 0; i < 5; i++) {
        ctx.rotate((Math.PI * 2) / 5);
        ctx.beginPath();
        ctx.ellipse(0, -8, 5, 8, 0, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.fillStyle = "#486337";
      ctx.beginPath();
      ctx.arc(0, 0, 4.5, 0, Math.PI * 2);
      ctx.fill();
    } else if (item.kind === "fig") {
      ctx.fillStyle = "#7b4f78";
      ctx.beginPath();
      ctx.ellipse(0, 2, 12, 14, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#9ec07b";
      ctx.beginPath();
      ctx.ellipse(5, -12, 6, 3, -0.5, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.fillStyle = "#6c874b";
      ctx.strokeStyle = "#3e5229";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.ellipse(0, 0, 11, 14, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "rgba(255,255,255,0.35)";
      ctx.beginPath();
      ctx.ellipse(-4, -5, 2.5, 5, 0, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }

  // Woven harvest basket with velocity lean.
  ctx.save();
  ctx.translate(world.basketX, ORCHARD.basketY + ORCHARD.basketHeight / 2);
  ctx.rotate(basketTilt);
  const hw = ORCHARD.basketWidth / 2;
  const hh = ORCHARD.basketHeight / 2;

  ctx.fillStyle = "#b5854d";
  ctx.strokeStyle = "#6f4d28";
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  ctx.moveTo(-hw, -hh);
  ctx.lineTo(hw, -hh);
  ctx.lineTo(hw - 10, hh);
  ctx.lineTo(-hw + 10, hh);
  ctx.closePath();
  ctx.fill();
  ctx.stroke();

  // Geometric weave accents
  ctx.strokeStyle = "rgba(111, 77, 40, 0.35)";
  ctx.lineWidth = 1.5;
  for (let wx = -hw + 16; wx <= hw - 16; wx += 16) {
    ctx.beginPath();
    ctx.moveTo(wx, -hh + 4);
    ctx.lineTo(wx * 0.84, hh - 2);
    ctx.stroke();
  }

  // Rim highlight.
  ctx.fillStyle = "#cfa068";
  ctx.beginPath();
  ctx.roundRect(-hw - 4, -hh - 4, ORCHARD.basketWidth + 8, 9, 4);
  ctx.fill();
  ctx.stroke();
  ctx.restore();

  // Score popups.
  for (const popup of world.popups) {
    ctx.globalAlpha = Math.max(0, Math.min(1, popup.life / 0.75));
    ctx.fillStyle = popup.positive ? "#2b5230" : "#8b3b30";
    ctx.font = "700 14px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(popup.text, popup.x, popup.y);
  }
  ctx.globalAlpha = 1;
}

export default function OrchardCatch() {
  const worldRef = useRef<OrchardWorld>(newOrchard());
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const heldDirection = useRef<-1 | 0 | 1>(0);
  const basketTiltRef = useRef(0);
  const prevBasketXRef = useRef(worldRef.current.basketX);
  const [phase, setPhase] = useState<OrchardPhase>("ready");
  const phaseRef = useRef<OrchardPhase>("ready");
  phaseRef.current = phase;

  const [hud, setHud] = useState({
    score: 0,
    streak: 0,
    lives: 3,
    wave: 1,
  });
  const [best, setBest] = useState(0);
  const [previousBest, setPreviousBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);

  useEffect(() => {
    let live = true;
    void readCover<number>("orchard-catch-best", 0)
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

  useEffect(() => {
    if (!storageReady || hud.score <= best) return;
    setBest(hud.score);
    void writeCover("orchard-catch-best", hud.score).catch(() => {});
  }, [best, hud.score, storageReady]);

  const syncHud = useCallback((w: OrchardWorld) => {
    setHud((prev) =>
      prev.score === w.score &&
      prev.streak === w.streak &&
      prev.lives === w.lives &&
      prev.wave === w.wave
        ? prev
        : { score: w.score, streak: w.streak, lives: w.lives, wave: w.wave },
    );
  }, []);

  const paint = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const targetW = ORCHARD.width * dpr;
    const targetH = ORCHARD.height * dpr;
    if (canvas.width !== targetW || canvas.height !== targetH) {
      canvas.width = targetW;
      canvas.height = targetH;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    drawOrchardCanvas(ctx, worldRef.current, basketTiltRef.current);
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
        worldRef.current = moveOrchardBasket(
          worldRef.current,
          worldRef.current.basketX + heldDirection.current * 440 * Math.min(dt, 0.032),
        );
      }
      const dx = worldRef.current.basketX - prevBasketXRef.current;
      prevBasketXRef.current = worldRef.current.basketX;
      basketTiltRef.current = Math.max(
        -0.16,
        Math.min(0.16, basketTiltRef.current * 0.8 + dx * 0.012),
      );
      const { world: next, events } = advanceOrchard(worldRef.current, dt);
      worldRef.current = next;
      if (events.caught && events.caught !== "pinecone") {
        if (events.caught === "blossom") gameSfx.powerup();
        else gameSfx.score();
      }
      if (events.missed) gameSfx.miss();
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
    const fresh = newOrchard();
    worldRef.current = fresh;
    basketTiltRef.current = 0;
    prevBasketXRef.current = fresh.basketX;
    phaseRef.current = "playing";
    setPhase("playing");
    syncHud(fresh);
    paint();
    gameSfx.tap();
    requestAnimationFrame(() => stageRef.current?.focus());
  }, [best, paint, syncHud]);

  const moveBasketToClientX = useCallback(
    (clientX: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const rect = canvas.getBoundingClientRect();
      if (!rect.width) return;
      const x = ((clientX - rect.left) / rect.width) * ORCHARD.width;
      const next = moveOrchardBasket(worldRef.current, x);
      const dx = next.basketX - prevBasketXRef.current;
      prevBasketXRef.current = next.basketX;
      basketTiltRef.current = Math.max(
        -0.16,
        Math.min(0.16, basketTiltRef.current * 0.72 + dx * 0.012),
      );
      worldRef.current = next;
      paint();
    },
    [paint],
  );

  return (
    <section className="grove-game arcade-game" aria-label="Orchard Catch game">
      <div className="game-score">
        <span>
          HARVEST <strong aria-live="polite">{hud.score}</strong>
        </span>
        <span>
          STREAK <strong>{hud.streak > 1 ? `${hud.streak}×` : "—"}</strong>
        </span>
        <span>
          LEAVES{" "}
          <strong>
            {Array.from({ length: 3 }, (_, i) => (
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
          aria-label="Orchard Catch board. Slide finger or use Arrow keys to move basket."
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
              if (phaseRef.current !== "playing") startRound();
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
            width={ORCHARD.width}
            height={ORCHARD.height}
            onPointerDown={(event) => {
              if (phaseRef.current !== "playing") return;
              event.currentTarget.setPointerCapture(event.pointerId);
              moveBasketToClientX(event.clientX);
            }}
            onPointerMove={(event) => {
              if (phaseRef.current !== "playing") return;
              if (event.buttons > 0 || event.pointerType === "mouse") {
                moveBasketToClientX(event.clientX);
              }
            }}
          />
          {phase === "ready" && (
            <GameOverlay
              tone="ready"
              icon={<Leaf size={22} />}
              title="Orchard Catch"
              subtitle="Catch ripe olives, figs, and golden blossoms in your woven basket. Dodge the pinecones."
              best={best || null}
              controlsHint="Slide your finger across the orchard, tap Left / Right buttons, or use Arrow keys"
              actionLabel="Start harvest"
              actionAriaLabel="Start Orchard Catch"
              onAction={startRound}
            />
          )}
          {phase === "paused" && (
            <GameOverlay
              tone="paused"
              title="Paused"
              subtitle="Rest a moment in the shade. Your basket stays ready."
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
              title="Basket full"
              subtitle={`You gathered ${hud.score} points by wave ${hud.wave}.`}
              score={hud.score}
              best={best}
              isNewBest={hud.score > 0 && hud.score > previousBest}
              actionLabel="Play again"
              actionAriaLabel="Play Orchard Catch again"
              onAction={startRound}
            />
          )}
        </div>
      </div>

      <div className="game-bottom touch-game-bottom">
        <p role="status" aria-live="polite">
          {phase === "ready"
            ? "Gather falling olives & blossoms."
            : phase === "paused"
              ? "Paused. Take your time."
              : phase === "over"
                ? `Harvest finished with ${hud.score} points.`
                : `Wave ${hud.wave} · Catch fruit, dodge pinecones.`}
        </p>
        <div className="touch-arcade-controls">
          <div className="touch-steer-pad" role="group" aria-label="Basket controls">
            <button
              type="button"
              aria-label="Move basket left"
              onPointerDown={(e) => {
                e.preventDefault();
                heldDirection.current = -1;
              }}
              onPointerUp={() => (heldDirection.current = 0)}
              onPointerCancel={() => (heldDirection.current = 0)}
              onPointerLeave={() => (heldDirection.current = 0)}
              onClick={() => {
                worldRef.current = moveOrchardBasket(
                  worldRef.current,
                  worldRef.current.basketX - 38,
                );
                paint();
              }}
            >
              <ArrowLeft size={20} aria-hidden="true" /> Left
            </button>
            <button
              type="button"
              aria-label="Move basket right"
              onPointerDown={(e) => {
                e.preventDefault();
                heldDirection.current = 1;
              }}
              onPointerUp={() => (heldDirection.current = 0)}
              onPointerCancel={() => (heldDirection.current = 0)}
              onPointerLeave={() => (heldDirection.current = 0)}
              onClick={() => {
                worldRef.current = moveOrchardBasket(
                  worldRef.current,
                  worldRef.current.basketX + 38,
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
