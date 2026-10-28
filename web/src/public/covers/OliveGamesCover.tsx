// Cover users: shows the olive games cover.
import { useEffect, useRef, useState, type CSSProperties } from "react";
import {
  Leaf,
  RotateCcw,
  Flower2,
  Sun,
  Cloud,
  Moon,
  Sprout,
  TreePine,
} from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { readCover, writeCover } from "../../services/CoverStateStore";
import { useSession } from "../../session/SessionProvider";
import OliveFlight from "./OliveFlight";
import OliveCrossing from "./OliveCrossing";
import { createMemoryBoard } from "./olive-memory-board";
const symbols = [Leaf, Flower2, Sun, Cloud, Moon, TreePine];
export default function Game({ landing = false }: { landing?: boolean }) {
  const session = useSession();
  const entryTaps = useRef<number[]>([]);
  const Content = landing ? "main" : "div";
  const [game, setGame] = useState<"pairs" | "flight" | "crossing">("pairs");
  const [size, setSize] = useState(5);
  const pairCount = Math.floor((size * size) / 2);
  const [cards, setCards] = useState(() => createMemoryBoard(5));
  const [flipped, setFlipped] = useState<number[]>([]);
  const [matched, setMatched] = useState<number[]>([]);
  const [moves, setMoves] = useState(0);
  const [best, setBest] = useState<number | null>(null);
  const [storageReady, setStorageReady] = useState(false);
  useEffect(() => {
    let live = true;
    setStorageReady(false);
    setBest(null);
    void readCover<number | null>(`grove-best-${size}`, null)
      .then((v) => {
        if (!live) return;
        setBest(v);
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
    const timer = setTimeout(
      () => {
        if (cards[a] === cards[b]) setMatched((old) => [...old, a, b]);
        setFlipped([]);
      },
      cards[a] === cards[b] ? 350 : 1000,
    );
    return () => clearTimeout(timer);
  }, [flipped, cards]);
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
    setFlipped((old) => [...old, index]);
    if (flipped.length === 1) setMoves((n) => n + 1);
  }
  function restart(nextSize = size) {
    setSize(nextSize);
    setCards(createMemoryBoard(nextSize));
    setFlipped([]);
    setMatched([]);
    setMoves(0);
  }
  function tapBrand() {
    const now = Date.now();
    entryTaps.current = [
      ...entryTaps.current.filter((tap) => now - tap < 2500),
      now,
    ];
    if (entryTaps.current.length < 5) return;
    entryTaps.current = [];
    void session.perform(session.beginEntry);
  }
  return (
    <div className={`grove ${landing ? "grove-landing" : ""}`}>
      <header className="grove-header">
        <button type="button" className="grove-brand" onClick={tapBrand}>
          <Sprout strokeWidth={1.4} />
          <span>
            little grove<small>A SMALL DAILY PAUSE</small>
          </span>
        </button>
      </header>
      <Content id={landing ? "main" : undefined} className="grove-main">
        <div className="grove-intro">
          <span className="eyebrow">
            {game === "pairs"
              ? "TAKE A BREATH. FIND A PAIR."
              : game === "flight"
                ? "SPREAD YOUR LEAVES. TAKE FLIGHT."
                : "ONE BRAVE OLIVE. ONE BUSY ROAD."}
          </span>
          <h1>
            A little less rush.
            <br />
            <em>A little more play.</em>
          </h1>
          <p>
            {game === "pairs" ? (
              <>
                {pairCount} pairs. One little discovery at a time.
                <br />
                Turn over two cards and find what belongs together.
              </>
            ) : game === "flight" ? (
              <>
                One little olive. A whole grove to explore.
                <br />
                Find your rhythm and flutter between the branches.
              </>
            ) : (
              <>
                Help a little olive cross the busy grove paths.
                <br />
                Keep moving, watch the carts, and see how far you get.
              </>
            )}
          </p>
          <div className="grove-games" role="group" aria-label="Choose a game">
            <button
              aria-pressed={game === "pairs"}
              onClick={() => setGame("pairs")}
            >
              Nature Pairs
            </button>
            <button
              aria-pressed={game === "flight"}
              onClick={() => setGame("flight")}
            >
              Olive Flight
            </button>
            <button
              aria-pressed={game === "crossing"}
              onClick={() => setGame("crossing")}
            >
              Olive Crossing
            </button>
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
        </div>
        {game === "flight" ? (
          <OliveFlight />
        ) : game === "crossing" ? (
          <OliveCrossing />
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
            </div>
            <div className="game-bottom">
              <p role="status">
                {matched.length === pairCount * 2
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
        <span>PLAY A LITTLE. FEEL A LITTLE LIGHTER.</span>
      </footer>
    </div>
  );
}
