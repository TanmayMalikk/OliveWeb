// Cover users: Crossy Road-style Olive Crossing with selectable voxel Olive characters, coins, logs, and touch controls.
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Coins,
  Footprints,
  Lock,
  Pause,
  Play,
  RotateCcw,
} from "lucide-react";
import { Button } from "../../components/SharedComponents";
import CrossingBoard from "./OliveCrossingBoard";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  OLIVE_CHARACTERS,
  VoxelOlive,
  getOliveCharacter,
  type OliveCharacterId,
} from "./olive-crossing-characters";
import { readCover, writeCover } from "../../services/CoverStateStore";
import {
  CROSSING,
  advanceCrossing,
  moveCrossing,
  newCrossing,
  type CrossingDeathReason,
  type CrossingDirection,
  type CrossingWorld,
} from "./olive-crossing";

type CrossingPhase = "ready" | "running" | "paused" | "over";

const DEFAULT_UNLOCKED: OliveCharacterId[] = ["classic", "sproutling"];
const HAWK_LIMIT_SECONDS = 12;

function deathHeadline(reason?: CrossingDeathReason) {
  if (reason === "water") return "Splash! Watch the current";
  if (reason === "train") return "Mind the express train";
  if (reason === "eagle") return "Swooped by the orchard hawk";
  return "Mind the crossing";
}

function deathDetail(score: number, reason?: CrossingDeathReason) {
  if (reason === "water") {
    return `You hopped ${score} lanes before taking a dip. Aim for lily pads and drifting logs!`;
  }
  if (reason === "train") {
    return `You cleared ${score} lanes! Watch the crossing light turn red before rails rumble.`;
  }
  if (reason === "eagle") {
    return `Keep hopping forward after ${score} lanes — lingering draws the orchard hawk!`;
  }
  return `You crossed ${score} lanes. Pick an Olive or hop right back in!`;
}

export default function OliveCrossing() {
  const [world, setWorld] = useState<CrossingWorld>(() => newCrossing());
  const worldRef = useRef<CrossingWorld>(world);
  worldRef.current = world;

  const [phase, setPhase] = useState<CrossingPhase>("ready");
  const [best, setBest] = useState(0);
  const [previousBest, setPreviousBest] = useState(0);
  const [coins, setCoins] = useState(0);
  const [characterId, setCharacterId] = useState<OliveCharacterId>("classic");
  const [unlockedChars, setUnlockedChars] =
    useState<OliveCharacterId[]>(DEFAULT_UNLOCKED);
  const [storageReady, setStorageReady] = useState(false);

  const [hop, setHop] = useState(0);
  const [facing, setFacing] = useState<CrossingDirection>("up");
  const [eagleLevel, setEagleLevel] = useState(0);
  const stallRef = useRef(0);

  const [camera, setCamera] = useState({
    x: 0,
    width: CROSSING.columns * CROSSING.cell,
  });
  const cameraRef = useRef(camera);
  cameraRef.current = camera;

  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  const frameRef = useRef<number | null>(null);
  const lastFrame = useRef(0);
  const boardRef = useRef<HTMLDivElement>(null);
  const pointerStart = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    const board = boardRef.current;
    if (!board) return;
    const updateCamera = () => {
      const bounds = board.getBoundingClientRect();
      if (!bounds.width || !bounds.height) return;
      const fullWidth = CROSSING.columns * CROSSING.cell;
      if (window.matchMedia("(min-width: 48.01rem)").matches) {
        setCamera((current) =>
          current.x === 0 && current.width === fullWidth
            ? current
            : { x: 0, width: fullWidth },
        );
        return;
      }
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
    let live = true;
    Promise.all([
      readCover<number>("olive-crossing-best", 0),
      readCover<number>("olive-crossing-coins", 0),
      readCover<OliveCharacterId>("olive-crossing-character", "classic"),
      readCover<OliveCharacterId[]>("olive-crossing-unlocked", DEFAULT_UNLOCKED),
    ])
      .then(([savedBest, savedCoins, savedChar, savedUnlocked]) => {
        if (!live) return;
        setBest(Number.isFinite(savedBest) ? savedBest : 0);
        setPreviousBest(Number.isFinite(savedBest) ? savedBest : 0);
        setCoins(Number.isFinite(savedCoins) ? savedCoins : 0);
        setCharacterId(getOliveCharacter(savedChar).id);
        setUnlockedChars(
          Array.isArray(savedUnlocked) && savedUnlocked.length > 0
            ? savedUnlocked
            : DEFAULT_UNLOCKED,
        );
        setStorageReady(true);
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

  const awardCoin = useCallback(() => {
    setCoins((prev) => {
      const updated = prev + 1;
      void writeCover("olive-crossing-coins", updated).catch(() => {});
      return updated;
    });
  }, []);

  const playLossSound = useCallback((reason?: CrossingDeathReason) => {
    if (reason === "water") gameSfx.splash();
    else gameSfx.miss();
  }, []);

  const tick = useCallback(
    (time: number) => {
      if (phaseRef.current !== "running") return;
      const elapsed = lastFrame.current ? (time - lastFrame.current) / 1000 : 0;
      lastFrame.current = time;

      const current = worldRef.current;
      if (current.score > 0 && elapsed > 0) {
        const nextStall = stallRef.current + Math.min(elapsed, 0.05);
        stallRef.current = nextStall;
        const ratio = nextStall / HAWK_LIMIT_SECONDS;
        const quantized = ratio >= 0.6 ? Math.round(ratio * 10) / 10 : 0;
        setEagleLevel((prev) => (prev === quantized ? prev : quantized));

        if (nextStall >= HAWK_LIMIT_SECONDS) {
          const swooped: CrossingWorld = {
            ...current,
            over: true,
            deathReason: "eagle",
          };
          worldRef.current = swooped;
          phaseRef.current = "over";
          setWorld(swooped);
          setPhase("over");
          playLossSound("eagle");
          return;
        }
      }

      const next = advanceCrossing(current, elapsed);
      worldRef.current = next;
      setWorld(next);
      if (next.over && !current.over) {
        phaseRef.current = "over";
        setPhase("over");
        playLossSound(next.deathReason);
        return;
      }

      if (phaseRef.current === "running") {
        frameRef.current = requestAnimationFrame(tick);
      }
    },
    [playLossSound],
  );

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
      if (document.hidden && phaseRef.current === "running") {
        phaseRef.current = "paused";
        setPhase("paused");
      }
    };
    document.addEventListener("visibilitychange", handleVisibility);
    return () =>
      document.removeEventListener("visibilitychange", handleVisibility);
  }, []);

  const begin = useCallback(() => {
    setPreviousBest(best);
    stallRef.current = 0;
    setEagleLevel(0);
    setFacing("up");
    const fresh = newCrossing();
    worldRef.current = fresh;
    phaseRef.current = "running";
    setWorld(fresh);
    setHop((value) => value + 1);
    setPhase("running");
    gameSfx.tap();
    requestAnimationFrame(() => boardRef.current?.focus());
  }, [best]);

  const move = useCallback(
    (direction: CrossingDirection) => {
      if (phaseRef.current !== "running") return;
      setFacing(direction);
      setHop((value) => value + 1);

      const current = worldRef.current;
      const next = moveCrossing(current, direction);
      worldRef.current = next;
      setWorld(next);

      if (next.coinJustCollected) {
        awardCoin();
        gameSfx.coin();
      }
      if (next.over && !current.over) {
        phaseRef.current = "over";
        setPhase("over");
        playLossSound(next.deathReason);
      } else if (next.score > current.score) {
        stallRef.current = 0;
        setEagleLevel(0);
        if (!next.coinJustCollected) gameSfx.score();
      } else if (next !== current && !next.coinJustCollected) {
        gameSfx.hop();
      }
    },
    [awardCoin, playLossSound],
  );

  const handleSelectCharacter = (id: OliveCharacterId) => {
    const target = getOliveCharacter(id);
    if (unlockedChars.includes(id)) {
      setCharacterId(id);
      void writeCover("olive-crossing-character", id).catch(() => {});
      gameSfx.tap();
      return;
    }
    if (coins >= target.cost) {
      const nextCoins = coins - target.cost;
      const nextUnlocked = [...unlockedChars, id];
      setCoins(nextCoins);
      setUnlockedChars(nextUnlocked);
      setCharacterId(id);
      void writeCover("olive-crossing-coins", nextCoins).catch(() => {});
      void writeCover("olive-crossing-unlocked", nextUnlocked).catch(() => {});
      void writeCover("olive-crossing-character", id).catch(() => {});
      gameSfx.coin();
    }
  };

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

  // Supports both swipe gestures anywhere on the board and intuitive tap-relative-to-olive hops.
  const handleBoardPointerUp = (clientX: number, clientY: number) => {
    const start = pointerStart.current;
    pointerStart.current = null;
    if (!start || phaseRef.current !== "running") return;

    const dx = clientX - start.x;
    const dy = clientY - start.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) >= 22) {
      if (Math.abs(dx) > Math.abs(dy)) move(dx > 0 ? "right" : "left");
      else move(dy > 0 ? "down" : "up");
      return;
    }

    const board = boardRef.current;
    if (!board) return;
    const bounds = board.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    const cam = cameraRef.current;
    const w = worldRef.current;
    const tapWorldX = cam.x + ((clientX - bounds.left) / bounds.width) * cam.width;
    const tapWorldY =
      ((clientY - bounds.top) / bounds.height) * (CROSSING.rows * CROSSING.cell);
    const oliveX = w.playerColumn * CROSSING.cell + CROSSING.cell / 2;
    const oliveY = w.playerRow * CROSSING.cell + CROSSING.cell / 2;
    const relX = tapWorldX - oliveX;
    const relY = tapWorldY - oliveY;

    if (
      Math.abs(relX) > Math.abs(relY) * 1.15 &&
      Math.abs(relX) > CROSSING.cell * 0.6
    ) {
      move(relX > 0 ? "right" : "left");
    } else if (relY > CROSSING.cell * 0.75) {
      move("down");
    } else {
      move("up");
    }
  };

  const activeCharacter = getOliveCharacter(characterId);
  const isNewRecord =
    phase === "over" && world.score > 0 && world.score > previousBest;

  const status =
    phase === "ready"
      ? `Ready to cross with ${activeCharacter.name}.`
      : phase === "paused"
        ? "Paused. Take your time."
        : phase === "over"
          ? `The olive needs another route. You crossed ${world.score} lanes.`
          : `Crossing the grove. ${world.score} lanes cleared.`;

  const renderCharacterPicker = () => (
    <div
      className="crossing-char-picker"
      role="group"
      aria-label="Choose Olive character"
    >
      <div className="crossing-char-header">
        <span>{activeCharacter.name}</span>
        <span className="crossing-coin-bank">
          <Coins size={13} aria-hidden="true" /> {coins}
        </span>
      </div>
      <div className="crossing-char-grid">
        {OLIVE_CHARACTERS.map((char) => {
          const isUnlocked = unlockedChars.includes(char.id);
          const isSelected = characterId === char.id;
          const canUnlock = !isUnlocked && coins >= char.cost;
          return (
            <button
              key={char.id}
              type="button"
              className={`crossing-char-btn${isSelected ? " is-selected" : ""}`}
              disabled={!isUnlocked && !canUnlock}
              aria-pressed={isSelected}
              aria-label={
                isUnlocked
                  ? `Select ${char.name}`
                  : `Unlock ${char.name} for ${char.cost} coins`
              }
              onClick={() => handleSelectCharacter(char.id)}
            >
              <svg viewBox="-20 -34 40 48" width="28" height="32" aria-hidden="true">
                <VoxelOlive character={char} facing="down" />
              </svg>
              {!isUnlocked && (
                <span className="crossing-char-cost">
                  <Lock size={9} aria-hidden="true" /> {char.cost}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );

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
          COINS <strong>{coins}</strong>
        </span>
        <span>
          YOUR BEST <strong>{best || "—"}</strong>
        </span>
      </div>
      <div className="crossing-arena">
        <div
          ref={boardRef}
          className={`crossing-stage${phase === "over" ? " crossing-crashed" : ""}`}
          role="group"
          aria-label="Olive Crossing board. Swipe, tap direction buttons, or use arrow keys."
          tabIndex={0}
          onPointerDown={(event) => {
            if (phaseRef.current !== "running") return;
            pointerStart.current = { x: event.clientX, y: event.clientY };
          }}
          onPointerUp={(event) => {
            handleBoardPointerUp(event.clientX, event.clientY);
          }}
          onPointerCancel={() => {
            pointerStart.current = null;
          }}
          onKeyDown={(event) => {
            if (handleKey(event.key)) event.preventDefault();
            if (event.key === " " || event.key === "Enter") {
              event.preventDefault();
              if (phaseRef.current === "running") {
                phaseRef.current = "paused";
                setPhase("paused");
              } else if (phaseRef.current === "paused") {
                phaseRef.current = "running";
                setPhase("running");
              } else {
                begin();
              }
            }
          }}
        >
          <CrossingBoard
            camera={camera}
            hop={hop}
            world={world}
            character={activeCharacter}
            facing={facing}
            eagleProgress={eagleLevel}
          />
          {(phase === "ready" || phase === "paused" || phase === "over") && (
            <GameOverlay
              tone={
                phase === "over"
                  ? "over"
                  : phase === "paused"
                    ? "paused"
                    : "ready"
              }
              icon={phase === "ready" ? <Footprints size={22} /> : undefined}
              title={
                phase === "over"
                  ? deathHeadline(world.deathReason)
                  : phase === "paused"
                    ? "Paused"
                    : "Olive Crossing"
              }
              subtitle={
                phase === "over"
                  ? deathDetail(world.score, world.deathReason)
                  : phase === "paused"
                    ? "Take a breath. The road waits for you."
                    : "Hop across busy roads, tracks, lily pads, and drifting logs."
              }
              score={phase === "ready" ? undefined : world.score}
              scoreLabel="Lanes"
              best={best || null}
              isNewBest={isNewRecord}
              controlsHint="Swipe or tap on the board, use D-pad, or Arrow keys"
              actionLabel={
                phase === "over"
                  ? "Play again"
                  : phase === "paused"
                    ? "Resume"
                    : "Start game"
              }
              actionAriaLabel={
                phase === "over"
                  ? "Play Olive Crossing again"
                  : phase === "paused"
                    ? "Resume Olive Crossing"
                    : "Start Olive Crossing"
              }
              onAction={() => {
                if (phase === "paused") {
                  setPhase("running");
                  requestAnimationFrame(() => boardRef.current?.focus());
                } else {
                  begin();
                }
              }}
              secondaryLabel={phase === "paused" ? "New crossing" : undefined}
              onSecondary={phase === "paused" ? begin : undefined}
            >
              {phase !== "paused" ? renderCharacterPicker() : null}
            </GameOverlay>
          )}
        </div>
      </div>
      <div className="game-bottom crossing-bottom">
        <p role="status">{status}</p>
        <div className="crossing-actions">
          <div className="crossing-pad" aria-label="Move the olive">
            <button
              type="button"
              aria-label="Move up"
              onClick={() => move("up")}
            >
              <ArrowUp />
            </button>
            <button
              type="button"
              aria-label="Move left"
              onClick={() => move("left")}
            >
              <ArrowLeft />
            </button>
            <button
              type="button"
              aria-label="Move down"
              onClick={() => move("down")}
            >
              <ArrowDown />
            </button>
            <button
              type="button"
              aria-label="Move right"
              onClick={() => move("right")}
            >
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
        Swipe anywhere, tap a direction button, or use Arrow keys / WASD. Collect
        coins to unlock all 6 Olive characters.
      </p>
    </section>
  );
}
