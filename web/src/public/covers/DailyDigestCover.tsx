// News cover users: shows the daily digest cover.
import { useEffect, useState } from "react";
import DOMPurify from "dompurify";
import {
  Bookmark,
  ArrowRight,
  Search,
  RefreshCw,
  Newspaper,
} from "lucide-react";
import { Button, Empty, Modal, Branch, External } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import { type NewsArticle } from "../../domain/DomainModels";
import { readCover, writeCover } from "../../services/CoverStateStore";
export default function News() {
  const session = useSession();
  const [scope, setScope] = useState("local");
  const [place, setPlace] = useState("Canada");
  const [search, setSearch] = useState("");
  const [rows, setRows] = useState<NewsArticle[]>([]);
  const [saved, setSaved] = useState<NewsArticle[]>([]);
  const [savedOnly, setSavedOnly] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<NewsArticle | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const storageId = `${session.enrollment?.uid ?? "preview"}:news`;
  useEffect(() => {
    let live = true;
    void readCover<NewsArticle[]>(storageId, [])
      .then((value) => {
        if (live) {
          setSaved(value);
          setLoaded(true);
        }
      })
      .catch(() => setLoaded(true));
    return () => {
      live = false;
    };
  }, [storageId]);
  useEffect(() => {
    if (loaded)
      void writeCover(storageId, saved).catch(() =>
        setError("Unable to save bookmarks in this browser."),
      );
  }, [saved, loaded, storageId]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void fetch(`/api/news?scope=${scope}&q=${encodeURIComponent(place)}`, {
      signal: controller.signal,
    })
      .then(async (response) => {
        const value = await response.json();
        if (!response.ok) throw new Error(value.error);
        return value;
      })
      .then(setRows)
      .catch((e) => {
        if (e.name !== "AbortError")
          setError(e.message || "Unable to load news.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [scope, place, refresh]);
  function bookmark(article: NewsArticle) {
    setSaved((old) =>
      old.some((a) => a.id === article.id)
        ? old.filter((a) => a.id !== article.id)
        : [article, ...old],
    );
  }
  const visible = (savedOnly ? saved : rows).filter((row) =>
    `${row.title} ${row.topic} ${row.source}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  return (
    <div className="news-page">
      <div className="news-edition">
        <span>YOUR DAILY MOMENT OF PERSPECTIVE</span>
        <span>
          {new Date().toLocaleDateString([], {
            weekday: "long",
            day: "numeric",
            month: "long",
            year: "numeric",
          })}
        </span>
      </div>
      <header className="news-masthead">
        <span>THE</span>
        <h1>Daily Digest</h1>
        <p>A little closer to your world.</p>
      </header>
      <div className="news-toolbar">
        <div className="filter-row">
          <button
            aria-pressed={!savedOnly && scope === "local"}
            onClick={() => {
              setScope("local");
              setSavedOnly(false);
            }}
          >
            Around you
          </button>
          <button
            aria-pressed={!savedOnly && scope === "world"}
            onClick={() => {
              setScope("world");
              setSavedOnly(false);
            }}
          >
            The world
          </button>
          <button aria-pressed={savedOnly} onClick={() => setSavedOnly(true)}>
            <Bookmark size={15} /> Saved stories
          </button>
        </div>
        <button
          className="icon-button"
          aria-label="Refresh news"
          onClick={() => setRefresh((n) => n + 1)}
        >
          <RefreshCw size={18} />
        </button>
      </div>
      <div className="news-search-row">
        <label className="search-field">
          <Search size={18} />
          <span className="sr-only">Search stories</span>
          <input
            placeholder="Find a little perspective…"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              void session.searchTrigger(e.target.value).then((found) => {
                if (found) setSearch("");
              });
            }}
          />
        </label>
        {scope === "local" && !savedOnly && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setPlace(
                String(new FormData(e.currentTarget).get("location")).trim() ||
                  "Canada",
              );
            }}
          >
            <label>
              <span className="sr-only">Local news city</span>
              <input
                name="location"
                placeholder="City or region"
                defaultValue={place}
                maxLength={100}
              />
            </label>
            <Button variant="quiet" type="submit">
              Update region
            </Button>
          </form>
        )}
      </div>
      {error && (
        <div className="status-banner" role="status">
          {error}
          <button
            className="text-link"
            onClick={() => setRefresh((n) => n + 1)}
          >
            Retry
          </button>
        </div>
      )}
      {loading && (
        <p className="loading" role="status">
          Putting today’s edition together…
        </p>
      )}
      <div className="newspaper-grid">
        {visible.map((article, i) => (
          <article
            className={`newspaper-card ${i === 0 && !savedOnly ? "lead-story" : ""}`}
            key={article.id}
          >
            <div className="newspaper-card-top">
              <strong>{article.source}</strong>
              <button
                className="icon-button"
                onClick={() => bookmark(article)}
                aria-label={`${saved.some((a) => a.id === article.id) ? "Remove bookmark" : "Save article"}: ${article.title}`}
                aria-pressed={saved.some((a) => a.id === article.id)}
              >
                <Bookmark
                  size={20}
                  fill={
                    saved.some((a) => a.id === article.id)
                      ? "currentColor"
                      : "none"
                  }
                />
              </button>
            </div>
            <button
              className="article-open"
              onClick={() => setSelected(article)}
            >
              <div className="article-tags">
                <span className="tag">{article.topic}</span>
                {article.isPaywalled && (
                  <span className="paywall-tag">Paywall</span>
                )}
              </div>
              <h2>{article.title}</h2>
              {article.imageUrl ? (
                <img
                  src={article.imageUrl}
                  alt="Article photograph"
                  loading="lazy"
                />
              ) : (
                <div className="article-art" aria-hidden="true">
                  <Newspaper strokeWidth={0.8} />
                  <Branch />
                </div>
              )}
              <p>{article.summary}</p>
              <div className="article-bottom">
                <time>
                  {Number.isNaN(Date.parse(article.published))
                    ? ""
                    : new Date(article.published).toLocaleDateString([], {
                        day: "numeric",
                        month: "short",
                      })}
                </time>
                <ArrowRight size={22} />
              </div>
            </button>
          </article>
        ))}
      </div>
      {!visible.length && !loading && !error && (
        <Empty
          title={
            savedOnly
              ? "Keep a little perspective for later."
              : "No matching stories."
          }
        >
          {savedOnly
            ? "Use the bookmark button to save a story."
            : "Try another search or region."}
        </Empty>
      )}
      {selected && (
        <Reader
          article={selected}
          close={() => setSelected(null)}
          bookmark={() => bookmark(selected)}
          saved={saved.some((a) => a.id === selected.id)}
        />
      )}
    </div>
  );
}
// Reads er.
function Reader({
  article,
  close,
  bookmark,
  saved,
}: {
  article: NewsArticle;
  close: () => void;
  bookmark: () => void;
  saved: boolean;
}) {
  const [value, setValue] = useState<{
    title: string;
    content: string;
    byline?: string;
    url: string;
    isPaywalled?: boolean;
  } | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    void fetch(`/api/articles/${article.id}`, { signal: controller.signal })
      .then(async (response) => {
        const result = await response.json();
        if (!response.ok) throw new Error(result.error);
        return result;
      })
      .then(setValue)
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => controller.abort();
  }, [article.id]);
  return (
    <Modal title={article.source} onClose={close} wide>
      <div className="reader-actions">
        <span className="tag">{article.topic}</span>
        {value?.isPaywalled && <span className="paywall-tag">Paywall</span>}
        <Button variant="quiet" onClick={bookmark}>
          <Bookmark size={17} />
          {saved ? "Saved" : "Save article"}
        </Button>
      </div>
      <h1 className="reader-title">{value?.title ?? article.title}</h1>
      {value?.byline && <p>{value.byline}</p>}
      {error ? (
        <div className="reader-fallback">
          <p role="status">{error}</p>
          <p>
            Some publishers block web readers or require a subscription. Their
            access restrictions remain in place.
          </p>
        </div>
      ) : value ? (
        <div
          className="reader-body"
          dangerouslySetInnerHTML={{
            __html: DOMPurify.sanitize(value.content, {
              ALLOWED_TAGS: [
                "p",
                "h1",
                "h2",
                "h3",
                "h4",
                "ul",
                "ol",
                "li",
                "blockquote",
                "strong",
                "em",
                "a",
                "img",
                "figure",
                "figcaption",
                "br",
              ],
              ALLOWED_ATTR: ["href", "src", "alt", "title"],
              ALLOW_DATA_ATTR: false,
            }),
          }}
        />
      ) : (
        <p role="status">Opening the article…</p>
      )}
      <div className="reader-source">
        <External href={value?.url ?? article.link}>
          Read the original at {article.source}
        </External>
      </div>
    </Modal>
  );
}
