// Cover users: shows the Little Grove cover with 6 botanical games and touch-ready controls.
import { Suspense, lazy, useEffect, useState, type CSSProperties } from "react";
import {
  Apple,
  Cloud,
  Feather,
  Flower2,
  Footprints,
  LayoutGrid,
  Leaf,
  Moon,
  RotateCcw,
  Sparkles,
  Sprout,
  Sun,
  TreePine,
  Volume2,
  VolumeX,
} from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { GameOverlay } from "./GameOverlay";
import { gameSfx, isGameMuted, setGameMuted } from "./game-audio";
import { createMemoryBoard } from "./olive-memory-board";

const loadFlight = () => import("./OliveFlight");
const loadCrossing = () => import("./OliveCrossing");
const loadBreaker = () => import("./OliveBreaker");
const loadMerge = () => import("./GroveMerge");
const loadCatch = () => import("./OrchardCatch");

const OliveFlight = lazy(loadFlight);
const OliveCrossing = lazy(loadCrossing);
const OliveBreaker = lazy(loadBreaker);
const GroveMerge = lazy(loadMerge);
const OrchardCatch = lazy(loadCatch);

function preloadGame(id: GroveGameId) {
  if (id === "flight") void loadFlight();
  else if (id === "crossing") void loadCrossing();
  else if (id === "breaker") void loadBreaker();
  else if (id === "merge") void loadMerge();
  else if (id === "catch") void loadCatch();
}

type GroveGameId =
  | "pairs"
  | "flight"
  | "crossing"
  | "breaker"
  | "merge"
  | "catch";

const GAME_META: Record<
  GroveGameId,
  {
    label: string;
    eyebrow: string;
    description: [string, string];
    icon: typeof Flower2;
  }
> = {
  pairs: {
    label: "Nature Pairs",
    eyebrow: "Take a breath",
    description: [
      "One little discovery at a time.",
      "Turn over two cards and find what belongs together.",
    ],
    icon: Flower2,
  },
  flight: {
    label: "Olive Flight",
    eyebrow: "Steady rhythm",
    description: [
      "One little olive. A whole grove to explore.",
      "Find your rhythm and flutter between the branches.",
    ],
    icon: Feather,
  },
  crossing: {
    label: "Olive Crossing",
    eyebrow: "Mindful pace",
    description: [
      "Help a little olive cross the busy grove paths.",
      "Swipe or tap to steer, watch the carts, and cross safely.",
    ],
    icon: Footprints,
  },
  breaker: {
    label: "Canopy Breaker",
    eyebrow: "Arcade focus",
    description: [
      "Bounce the bouncing olive across leafy timber bricks.",
      "Catch wide-paddle, twin-olive, and calm-breeze boosts.",
    ],
    icon: Sparkles,
  },
  merge: {
    label: "Grove 2048",
    eyebrow: "Botanical puzzle",
    description: [
      "Slide tiles across the garden bed to evolve each stage.",
      "Swipe in any direction to grow from Seed up to Ancient Olive.",
    ],
    icon: LayoutGrid,
  },
  catch: {
    label: "Orchard Catch",
    eyebrow: "Harvest rush",
    description: [
      "Drag or tap to catch ripe olives, figs, and golden blossoms.",
      "Build harvest streaks while weaving past tumbling pinecones.",
    ],
    icon: Apple,
  },
};

const symbols = [Leaf, Flower2, Sun, Cloud, Moon, TreePine];

function GameCoverPlaceholder({
  id,
  label,
}: {
  id: GroveGameId;
  label: string;
}) {
  const arenaClass =
    id === "crossing"
      ? "crossing-arena"
      : id === "flight"
        ? "flight-arena"
        : id === "merge"
          ? "merge-arena"
          : "canvas-arena";
  const stageClass =
    id === "crossing"
      ? "crossing-stage"
      : id === "flight"
        ? "flight-stage"
        : id === "merge"
          ? "merge-board-wrap"
          : "canvas-stage";

  return (
    <section
      className="grove-game grove-cover-placeholder"
      aria-label={`${label} loading`}
      aria-busy="true"
    >
      <div className="game-score" aria-hidden="true">
        <span>
          SCORE <strong>—</strong>
        </span>
        <span>
          ROUND <strong>—</strong>
        </span>
        <span>
          YOUR BEST <strong>—</strong>
        </span>
      </div>
      <div className={arenaClass}>
        <div className={`${stageClass} grove-placeholder-stage`}>
          <div className="grove-placeholder-card">
            <Sprout size={24} aria-hidden="true" />
            <strong>{label}</strong>
            <span>Preparing stage…</span>
          </div>
        </div>
      </div>
      <div className="game-bottom">
        <p role="status">Loading {label}…</p>
      </div>
    </section>
  );
}

export default function Game({ landing = false }: { landing?: boolean }) {
  const Content = landing ? "main" : "div";
  const [game, setGame] = useState<GroveGameId>("pairs");
  const [muted, setMuted] = useState(() => isGameMuted());
  const [size, setSize] = useState(5);
  const pairCount = Math.floor((size * size) / 2);
  const [cards, setCards] = useState(() => createMemoryBoard(5));
  const [flipped, setFlipped] = useState<number[]>([]);
  const [matched, setMatched] = useState<number[]>([]);
  const [moves, setMoves] = useState(0);
  const [best, setBest] = useState<number | null>(null);
  const [previousBest, setPreviousBest] = useState<number | null>(null);
  const [storageReady, setStorageReady] = useState(false);

  useEffect(() => {
    let live = true;
    setStorageReady(false);
    setBest(null);
    void readCover<number | null>(`grove-best-${size}`, null)
      .then((v) => {
        if (!live) return;
        setBest(v);
        setPreviousBest(v);
        setStorageReady(true);
      })
      .catch(() => {
        if (live) setStorageReady(true);
      });
    return () => {
      live = false;
    };
  }, [size]);

  useEffect(() => {
    if (flipped.length !== 2) return;
    const [a, b] = flipped;
    const isMatch = cards[a] === cards[b];
    const timer = setTimeout(
      () => {
        if (isMatch) {
          setMatched((old) => {
            const next = [...old, a, b];
            if (next.length === pairCount * 2) gameSfx.win();
            else gameSfx.match();
            return next;
          });
        }
        setFlipped([]);
      },
      isMatch ? 320 : 880,
    );
    return () => clearTimeout(timer);
  }, [flipped, cards, pairCount]);

  useEffect(() => {
    if (
      matched.length === pairCount * 2 &&
      storageReady &&
      (best === null || moves < best)
    ) {
      setBest(moves);
      void writeCover(`grove-best-${size}`, moves).catch(() => {});
    }
  }, [matched, moves, best, storageReady, pairCount, size]);

  function turn(index: number) {
    if (
      flipped.length >= 2 ||
      flipped.includes(index) ||
      matched.includes(index)
    )
      return;
    gameSfx.tap();
    setFlipped((old) => [...old, index]);
    if (flipped.length === 1) setMoves((n) => n + 1);
  }

  function restart(nextSize = size) {
    setPreviousBest(best);
    setSize(nextSize);
    setCards(createMemoryBoard(nextSize));
    setFlipped([]);
    setMatched([]);
    setMoves(0);
    gameSfx.tap();
  }

  const toggleAudio = () => {
    const next = !muted;
    setGameMuted(next);
    setMuted(next);
    if (!next) gameSfx.tap();
  };

  const completed = matched.length === pairCount * 2;
  const activeCopy = GAME_META[game];

  return (
    <div className={`grove ${landing ? "grove-landing" : ""}`}>
      <header className="grove-header">
        <div className="grove-brand">
          <span className="grove-brand-mark" aria-hidden="true">
            <Sprout size={20} strokeWidth={1.75} />
          </span>
          <span>
            Little Grove<small>Daily workspace</small>
          </span>
        </div>
        <div className="grove-header-tools">
          <span className="grove-header-pill">{activeCopy.label}</span>
          <button
            type="button"
            className="grove-sound-btn"
            aria-pressed={!muted}
            aria-label={muted ? "Unmute game sounds" : "Mute game sounds"}
            onClick={toggleAudio}
          >
            {muted ? (
              <VolumeX size={16} aria-hidden="true" />
            ) : (
              <Volume2 size={16} aria-hidden="true" />
            )}
            <span>{muted ? "Sound off" : "Sound on"}</span>
          </button>
        </div>
      </header>
      <Content id={landing ? "main" : undefined} className="grove-main">
        <aside className="grove-intro" aria-label="Little Grove library">
          <div className="grove-intro-head">
            <span className="eyebrow">{activeCopy.eyebrow}</span>
            <h1>{activeCopy.label}</h1>
            <p>
              {game === "pairs"
                ? `${pairCount} pairs. ${activeCopy.description[0]}`
                : activeCopy.description[0]}{" "}
              {activeCopy.description[1]}
            </p>
          </div>
          <div className="grove-rail-section">
            <p className="grove-rail-heading">Games</p>
            <div className="grove-games" role="group" aria-label="Choose a game">
              {(Object.keys(GAME_META) as GroveGameId[]).map((id) => {
                const NavIcon = GAME_META[id].icon;
                return (
                  <button
                    key={id}
                    type="button"
                    aria-pressed={game === id}
                    onMouseEnter={() => preloadGame(id)}
                    onFocus={() => preloadGame(id)}
                    onClick={() => {
                      setGame(id);
                      gameSfx.tap();
                    }}
                  >
                    <NavIcon size={17} aria-hidden="true" />
                    <span>{GAME_META[id].label}</span>
                  </button>
                );
              })}
            </div>
          </div>
          {game === "pairs" && (
            <div className="memory-options">
              <label htmlFor="memory-size">Board size</label>
              <select
                id="memory-size"
                value={size}
                onChange={(event) => restart(Number(event.target.value))}
              >
                {Array.from({ length: 4 }, (_, index) => index + 5).map(
                  (value) => (
                    <option key={value} value={value}>
                      {value} × {value}
                    </option>
                  ),
                )}
              </select>
              <small>
                {size % 2
                  ? "One free center space. Match the numbered pairs."
                  : "Match the numbered pairs. Best scores are saved per size."}{" "}
                Changing size starts a new round.
              </small>
            </div>
          )}
          <div className="grove-sidebar-bottom">
            <span className="grove-rail-badge" aria-hidden="true">
              <Sprout size={16} />
            </span>
            <div>
              <strong>No hurry</strong>
              <small>Touch, swipe, or keyboard</small>
            </div>
          </div>
        </aside>

        {game !== "pairs" ? (
          <Suspense
            fallback={
              <GameCoverPlaceholder id={game} label={activeCopy.label} />
            }
          >
            {game === "flight" ? (
              <OliveFlight />
            ) : game === "crossing" ? (
              <OliveCrossing />
            ) : game === "breaker" ? (
              <OliveBreaker />
            ) : game === "merge" ? (
              <GroveMerge />
            ) : (
              <OrchardCatch />
            )}
          </Suspense>
        ) : (
          <section className="grove-game" aria-label="Nature pairs memory game">
            <div className="game-score">
              <span>
                PAIRS{" "}
                <strong>
                  {matched.length / 2}
                  <small> / {pairCount}</small>
                </strong>
              </span>
              <span>
                MOVES <strong aria-live="polite">{moves}</strong>
              </span>
              <span>
                YOUR BEST <strong>{best ?? "—"}</strong>
              </span>
            </div>
            <div className="memory-arena">
              <div
                className="memory-grid"
                style={{ "--board-size": size } as CSSProperties}
              >
                {cards.map((symbol, index) => {
                  if (symbol === -1)
                    return (
                      <div
                        key={index}
                        className="memory-card memory-free"
                        aria-label="Free center space"
                      >
                        <Sprout aria-hidden="true" />
                        <span>FREE</span>
                      </div>
                    );
                  const Icon = symbols[symbol % symbols.length];
                  const visible =
                    flipped.includes(index) || matched.includes(index);
                  return (
                    <button
                      key={index}
                      className={`memory-card ${visible ? "turned" : ""} ${matched.includes(index) ? "matched" : ""}`}
                      onClick={() => turn(index)}
                      aria-label={
                        visible
                          ? `Card ${index + 1}: pair ${symbol + 1}, ${["leaf", "flower", "sun", "cloud", "moon", "tree"][symbol % symbols.length]}${matched.includes(index) ? ", matched" : ""}`
                          : `Turn card ${index + 1}`
                      }
                      aria-disabled={matched.includes(index)}
                    >
                      <span>
                        {visible ? (
                          <>
                            <Icon
                              size={44}
                              strokeWidth={1.2}
                              aria-hidden="true"
                            />
                            <b>{symbol + 1}</b>
                          </>
                        ) : (
                          <Sprout size={30} strokeWidth={1} />
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>
              {completed && (
                <GameOverlay
                  tone="won"
                  title="A lovely little finish"
                  subtitle={`All ${pairCount} pairs matched in ${moves} moves.`}
                  score={moves}
                  scoreLabel="Moves"
                  best={best}
                  bestLabel="Best"
                  isNewBest={previousBest === null || moves < previousBest}
                  actionLabel="Play again"
                  actionAriaLabel="Play Nature Pairs again"
                  onAction={() => restart()}
                />
              )}
            </div>
            <div className="game-bottom">
              <p role="status">
                {completed
                  ? "A lovely little finish. Play another round?"
                  : "No timer. No hurry. Just a little discovery."}
              </p>
              <Button variant="quiet" onClick={() => restart()}>
                <RotateCcw size={16} /> New round
              </Button>
            </div>
          </section>
        )}
      </Content>
      <footer className="grove-footer">
        <span>Nothing to chase. A moment to enjoy.</span>
        <span>Play a little. Feel a little lighter.</span>
      </footer>
    </div>
  );
}
