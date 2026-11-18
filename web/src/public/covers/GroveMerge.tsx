// Cover users: 1:1 2048 gameplay with smooth GPU tile sliding, touch/mouse swipe, and Olive Branch botanical tile assets.
import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  RotateCcw,
} from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx } from "./game-audio";
import {
  MERGE_STAGES,
  moveGroveMerge,
  newGroveMerge,
  type GroveMergeWorld,
  type MergeDirection,
} from "./grove-merge";

// Renders our Olive Branch botanical artwork per 2048 tile stage.
function GroveTileAsset({ value }: { value: number }) {
  if (value <= 2) {
    // Seed: smooth olive pit
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <ellipse cx="16" cy="17" rx="7" ry="10" fill="#8b6f47" stroke="#5b462a" strokeWidth="1.8" />
        <ellipse cx="14" cy="14" rx="2.2" ry="4" fill="#c7ab7c" opacity="0.65" />
      </svg>
    );
  }
  if (value === 4) {
    // Sprout (mirrors /icons/game.svg emblem)
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <path d="M16 26V13" stroke="#456b34" strokeWidth="2.4" strokeLinecap="round" />
        <path d="M16 17C10 17 7 12 8 7C13 7 17 11 16 17Z" fill="#6ca148" stroke="#365924" strokeWidth="1.3" />
        <path d="M16 14C16 8 20 5 25 5C25 10 21 15 16 14Z" fill="#8bc25f" stroke="#365924" strokeWidth="1.3" />
      </svg>
    );
  }
  if (value === 8) {
    // Leaf
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <path d="M7 24C9 13 16 7 25 7C25 16 19 23 7 24Z" fill="#5e9142" stroke="#2d4c1f" strokeWidth="1.5" />
        <path d="M7 24L21 11" stroke="#c8e39e" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    );
  }
  if (value === 16) {
    // Bud
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <path d="M16 27V18" stroke="#385b2c" strokeWidth="2.3" strokeLinecap="round" />
        <ellipse cx="16" cy="12" rx="6" ry="8" fill="#e9b949" stroke="#855f16" strokeWidth="1.5" />
        <path d="M10 17Q16 21 22 17" fill="none" stroke="#4b7537" strokeWidth="2.5" strokeLinecap="round" />
      </svg>
    );
  }
  if (value === 32) {
    // Little Olive (mirrors OliveFlight hero)
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <ellipse cx="16" cy="18" rx="8.5" ry="10.5" fill="#6b8e3e" stroke="#2f4719" strokeWidth="1.5" />
        <ellipse cx="13" cy="14" rx="2.2" ry="4" fill="#dbe6a3" opacity="0.6" />
        <path d="M16 8Q22 2 26 6Q22 10 16 8Z" fill="#8bc056" stroke="#3b5922" strokeWidth="1.2" />
      </svg>
    );
  }
  if (value === 64) {
    // Olive Branch Sprig (mirrors /icons/olive.svg)
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <path d="M6 25C12 18 19 12 27 6" stroke="#dceacb" strokeWidth="2.2" strokeLinecap="round" />
        <ellipse cx="13" cy="13" rx="3.2" ry="5" transform="rotate(-30 13 13)" fill="#e8f3dc" />
        <ellipse cx="21" cy="17" rx="3.2" ry="5" transform="rotate(40 21 17)" fill="#e8f3dc" />
        <ellipse cx="13" cy="22" rx="3.5" ry="4.5" fill="#f6d865" />
      </svg>
    );
  }
  if (value === 128) {
    // Blossom
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        {[0, 72, 144, 216, 288].map((deg) => (
          <ellipse
            key={deg}
            cx="16"
            cy="9"
            rx="3.4"
            ry="5.5"
            fill="#f7e28b"
            transform={`rotate(${deg} 16 16)`}
          />
        ))}
        <circle cx="16" cy="16" r="4.2" fill="#e59d2c" />
      </svg>
    );
  }
  if (value === 256) {
    // Branch Timber
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="4" y="14" width="24" height="9" rx="4" fill="#d9b07e" stroke="#613a18" strokeWidth="1.5" />
        <ellipse cx="25" cy="18.5" rx="3" ry="4.5" fill="#f3dfb8" />
        <path d="M13 14Q14 6 20 8Q18 13 13 14Z" fill="#9bd16f" />
      </svg>
    );
  }
  if (value === 512) {
    // Canopy Tree (mirrors OliveCrossingBoard Voxel Tree)
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="13.5" y="19" width="5" height="9" rx="1" fill="#f1d3a8" />
        <rect x="6" y="11" width="20" height="10" rx="2.5" fill="#9fd174" />
        <rect x="9" y="5" width="14" height="8" rx="2" fill="#c7e89b" />
      </svg>
    );
  }
  if (value === 1024) {
    // Golden Harvest Olive (mirrors OliveCrossing Golden Harvest hat)
    return (
      <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="8" y="12" width="16" height="14" rx="4" fill="#f4cb54" stroke="#7d560f" strokeWidth="1.3" />
        <ellipse cx="16" cy="12" rx="12" ry="3.2" fill="#f7e4ad" stroke="#855f16" strokeWidth="1.2" />
        <rect x="10" y="6" width="12" height="6" rx="1.5" fill="#fff2cc" />
        <circle cx="13" cy="18" r="1.4" fill="#231c0d" />
        <circle cx="19" cy="18" r="1.4" fill="#231c0d" />
      </svg>
    );
  }
  // 2048+ Ancient Olive Crown Crest
  return (
    <svg className="merge-tile-art" viewBox="0 0 32 32" aria-hidden="true">
      <polygon
        points="6,21 8,10 13,15 16,7 19,15 24,10 26,21"
        fill="#f7dc72"
        stroke="#9b7114"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <rect x="7" y="21" width="18" height="4" rx="1.5" fill="#e2b43b" />
    </svg>
  );
}

const ROUND_KEY = "grove-merge-round";
let pendingRoundWrite = Promise.resolve();

export default function GroveMerge() {
  const [world, setWorld] = useState<GroveMergeWorld>(() => newGroveMerge());
  const [best, setBest] = useState(0);
  const [previousBest, setPreviousBest] = useState(0);
  const [storageReady, setStorageReady] = useState(false);
  const pointerStart = useRef<{ x: number; y: number; id: number } | null>(null);
  const boardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let live = true;
    void Promise.all([
      readCover<number>("grove-merge-best", 0).catch(() => 0),
      pendingRoundWrite
        .then(() => readCover<GroveMergeWorld | null>(ROUND_KEY, null))
        .catch(() => null),
    ]).then(([value, saved]) => {
      if (!live) return;
      setBest(value);
      setPreviousBest(value);
      if (saved && !saved.over) setWorld(saved);
      setStorageReady(true);
    });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (!storageReady || world.score <= best) return;
    setBest(world.score);
    void writeCover("grove-merge-best", world.score).catch(() => {});
  }, [best, storageReady, world.score]);

  useEffect(() => {
    if (!storageReady) return;
    const saved = world.over ? null : {
      ...world,
      lastGained: 0,
      grid: world.grid.map((tile) => tile && ({ id: tile.id, value: tile.value })),
    };
    pendingRoundWrite = pendingRoundWrite
      .then(() => writeCover(ROUND_KEY, saved))
      .catch(() => {});
  }, [storageReady, world]);

  const restart = useCallback(() => {
    if (!storageReady) return;
    setPreviousBest(best);
    setWorld(newGroveMerge());
    gameSfx.tap();
    requestAnimationFrame(() => boardRef.current?.focus());
  }, [best, storageReady]);

  const step = useCallback(
    (direction: MergeDirection) => {
      if (!storageReady) return;
      const result = moveGroveMerge(world, direction);
      if (!result.moved) return;
      setWorld(result.world);
      if (result.world.won && !world.won) gameSfx.win();
      else if (result.mergedLevel > 0) gameSfx.merge(result.mergedLevel);
      else gameSfx.tap();
    },
    [storageReady, world],
  );

  const highestTile = world.grid.reduce(
    (max, tile) => (tile && tile.value > max ? tile.value : max),
    2,
  );

  // Build flat positioned tile nodes keyed by persistent tile.id so CSS smoothly slides tiles across cells.
  const tileNodes: Array<{
    key: string;
    value: number;
    row: number;
    col: number;
    merged?: boolean;
    isNew?: boolean;
    isGhost?: boolean;
  }> = [];

  world.grid.forEach((tile, index) => {
    if (!tile) return;
    const row = Math.floor(index / 4);
    const col = index % 4;
    if (tile.mergedFrom) {
      for (const src of tile.mergedFrom) {
        tileNodes.push({
          key: `tile-${src.id}`,
          value: src.value,
          row,
          col,
          isGhost: true,
        });
      }
    }
    tileNodes.push({
      key: `tile-${tile.id}`,
      value: tile.value,
      row,
      col,
      merged: tile.merged,
      isNew: tile.isNew,
    });
  });

  return (
    <section className="grove-game merge-game" aria-label="Grove 2048 game">
      <div className="game-score">
        <span>
          SCORE{" "}
          <strong aria-live="polite">
            {world.score}
            {Boolean(world.lastGained) && (
              <em key={world.moves} className="merge-score-delta">
                +{world.lastGained}
              </em>
            )}
          </strong>
        </span>
        <span>
          HIGHEST <strong>{MERGE_STAGES[highestTile] ?? highestTile}</strong>
        </span>
        <span>
          MOVES <strong>{world.moves}</strong>
        </span>
        <span>
          YOUR BEST <strong>{best || "—"}</strong>
        </span>
      </div>

      <div className="merge-arena">
        <div
          ref={boardRef}
          className="merge-board-wrap"
          role="group"
          aria-label="Grove 2048 board. Swipe on touch screen or use Arrow keys."
          tabIndex={0}
          onKeyDown={(event) => {
            const map: Record<string, MergeDirection> = {
              ArrowUp: "up",
              ArrowDown: "down",
              ArrowLeft: "left",
              ArrowRight: "right",
              w: "up",
              W: "up",
              s: "down",
              S: "down",
              a: "left",
              A: "left",
              d: "right",
              D: "right",
              k: "up",
              j: "down",
              h: "left",
              l: "right",
            };
            const dir = map[event.key];
            if (dir && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
              event.preventDefault();
              step(dir);
            }
          }}
          onPointerDown={(event) => {
            if (!event.isPrimary || event.button !== 0) return;
            event.currentTarget.focus();
            event.currentTarget.setPointerCapture(event.pointerId);
            pointerStart.current = { x: event.clientX, y: event.clientY, id: event.pointerId };
          }}
          onPointerUp={(event) => {
            if (pointerStart.current?.id !== event.pointerId) return;
            const { x, y } = pointerStart.current;
            pointerStart.current = null;
            const dx = event.clientX - x;
            const dy = event.clientY - y;
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= 10) return;
            if (Math.abs(dx) > Math.abs(dy)) step(dx > 0 ? "right" : "left");
            else step(dy > 0 ? "down" : "up");
          }}
          onPointerCancel={() => {
            pointerStart.current = null;
          }}
        >
          <div className="merge-board-stage">
            <div className="merge-grid merge-grid-wells" aria-hidden="true">
              {Array.from({ length: 16 }, (_, index) => (
                <div key={index} className="merge-cell merge-empty" />
              ))}
            </div>
            <div className="merge-tiles-layer">
              {tileNodes.map((node) => (
                <div
                  key={node.key}
                  aria-hidden={node.isGhost || undefined}
                  style={
                    {
                      "--row": node.row,
                      "--col": node.col,
                    } as CSSProperties
                  }
                  className={`merge-tile-node${node.isGhost ? " merge-tile-ghost" : ""}`}
                >
                  <div
                    className={`merge-cell merge-tile merge-v-${Math.min(4096, node.value)}${node.merged ? " merge-pop" : ""}${node.isNew ? " merge-spawn" : ""}`}
                    aria-label={
                      node.isGhost
                        ? undefined
                        : `${MERGE_STAGES[node.value] ?? node.value} (${node.value})`
                    }
                  >
                    <GroveTileAsset value={node.value} />
                    <strong>{node.value}</strong>
                    <small>{MERGE_STAGES[node.value] ?? "Grove"}</small>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {!world.over && world.won && !world.keepPlaying && (
            <GameOverlay
              tone="won"
              title="Ancient Olive grown!"
              subtitle="You reached 2048 in the grove. Keep merging or begin a fresh grove."
              score={world.score}
              best={best}
              isNewBest={world.score > previousBest}
              actionLabel="Keep playing"
              onAction={() => {
                setWorld((curr) => ({ ...curr, keepPlaying: true }));
                boardRef.current?.focus();
              }}
              secondaryLabel="New grove"
              onSecondary={restart}
            />
          )}

          {world.over && (
            <GameOverlay
              tone="over"
              title="Grove is full"
              subtitle={`No more merges remain. Highest stage: ${MERGE_STAGES[highestTile] ?? highestTile}.`}
              score={world.score}
              best={best}
              isNewBest={world.score > 0 && world.score > previousBest}
              actionLabel="Play again"
              onAction={restart}
            />
          )}
        </div>
      </div>

      <div className="game-bottom crossing-bottom">
        <p role="status" aria-live="polite">
          {world.over
            ? `No moves left. Final score ${world.score}.`
            : "Swipe on the grid or use Arrow keys to slide."}
        </p>
        <div className="crossing-actions">
          <div className="crossing-pad" aria-label="Slide tiles">
            <button type="button" aria-label="Slide up" onClick={() => step("up")}>
              <ArrowUp aria-hidden="true" />
            </button>
            <button type="button" aria-label="Slide left" onClick={() => step("left")}>
              <ArrowLeft aria-hidden="true" />
            </button>
            <button type="button" aria-label="Slide down" onClick={() => step("down")}>
              <ArrowDown aria-hidden="true" />
            </button>
            <button type="button" aria-label="Slide right" onClick={() => step("right")}>
              <ArrowRight aria-hidden="true" />
            </button>
          </div>
          <div className="crossing-round-actions">
            <Button variant="quiet" onClick={restart}>
              <RotateCcw size={16} aria-hidden="true" /> New round
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
