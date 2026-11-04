// Public web visitors: shows the discreet public game landing page.
import {
  ArrowUpRight,
  ArrowRight,
  Check,
  LockKeyhole,
  Newspaper,
  Dumbbell,
  Utensils,
  Gamepad2,
  MoveUpRight,
} from "lucide-react";
import { Branch, Logo, Button, Forward } from "../components/SharedComponents";
import { covers, coverPath, type Cover } from "../domain/DomainModels";
import { useSession } from "../session/SessionProvider";
import { siteHref } from "../sites/SiteRouting";
export const coverIcons = {
  FOOD: Utensils,
  NEWS: Newspaper,
  FITNESS: Dumbbell,
  GAME: Gamepad2,
};
// Shows a playable preview of the selected web game cover.
export function CoverPreview({ cover }: { cover: Cover }) {
  return (
    <div
      className={`cover-preview preview-theme-${cover.toLowerCase()}`}
      aria-hidden="true"
    >
      {cover === "FOOD" ? (
        <>
          <div className="preview-masthead">
            olive<span>GOOD FOOD, EVERY DAY.</span>
          </div>
          <div className="preview-food">
            <img src="/images/menu_bowl.jpg" alt="" />
            <span>
              Made for
              <br />
              <em>your kind of day.</em>
            </span>
          </div>
        </>
      ) : cover === "NEWS" ? (
        <>
          <div className="preview-masthead newspaper">DAILY DIGEST</div>
          <div className="preview-news">
            <small>THE WORLD, A LITTLE CLOSER</small>
            <strong>
              A fresh perspective.
              <br />
              Every single day.
            </strong>
            <div className="newspaper-lines" />
            <div className="preview-news-photo">
              <Branch />
            </div>
          </div>
        </>
      ) : cover === "FITNESS" ? (
        <>
          <div className="preview-masthead">Fitness Tracker</div>
          <div className="preview-result">
            Today <strong>8,650 steps</strong>
          </div>
          <div className="preview-keys">
            {["Steps", "Distance", "Calories", "Vitals"].map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
        </>
      ) : (
        <>
          <div className="preview-masthead">A little pause.</div>
          <div className="game-art">
            <Branch />
            <span>Something good is growing.</span>
          </div>
        </>
      )}
    </div>
  );
}
export default function Landing() {
  const { go } = useSession();
  return (
    <div className="landing">
      <header className="site-header">
        <Logo />
        <nav aria-label="Main navigation">
          <a href="#your-everyday">The everyday apps</a>
          <a href="#how-it-works">How it works</a>
        </nav>
        <div className="header-actions">
          <Button variant="quiet" onClick={() => go("/account/sign-in")}>
            Sign in <ArrowUpRight size={16} />
          </Button>
          <Button onClick={() => go("/account/create")}>
            Get started <ArrowRight size={17} />
          </Button>
        </div>
      </header>
      <main id="main">
        <section className="landing-hero page-width">
          <div className="hero-copy">
            <div className="eyebrow">
              <span className="status-dot" /> A LITTLE MORE ROOM TO BE YOU
            </div>
            <h1>
              Your everyday.
              <br />
              Your space.
              <br />
              <em>Your Olive Branch.</em>
            </h1>
            <p>
              A familiar place on the surface. A personal space underneath.
              Start with an everyday app that feels like you.
            </p>
            <div className="hero-actions">
              <Button onClick={() => go("/account/create")}>
                <Forward>Find your everyday</Forward>
              </Button>
              <button
                className="text-link"
                onClick={() => go("/account/sign-in")}
              >
                Already have an account? <ArrowUpRight size={17} />
              </button>
            </div>
            <div className="hero-note">
              <LockKeyhole size={15} />
              <span>Your account. Your choice. Your own pace.</span>
            </div>
          </div>
          <div className="hero-art">
            <div className="art-orbit" />
            <Branch className="hero-branch" />
            <div className="art-label">
              <span className="status-dot" /> SOMETHING GOOD IS GROWING
            </div>
            <div className="floating-cover hero-food">
              <CoverPreview cover="FOOD" />
            </div>
            <div className="floating-cover hero-news">
              <CoverPreview cover="NEWS" />
            </div>
            <div className="art-caption">
              A familiar face.
              <br />
              <em>A place that’s yours.</em>
            </div>
            <span className="art-index">01 — 04</span>
          </div>
        </section>
        <div className="values-strip">
          <span>Designed around you</span>
          <i>✳</i>
          <span>Familiar on the outside</span>
          <i>✳</i>
          <span>A personal space within</span>
          <i>✳</i>
          <span>At home on every screen</span>
        </div>
        <section className="page-width cover-section" id="your-everyday">
          <div className="section-heading">
            <div>
              <span className="eyebrow">FIND YOUR FAMILIAR</span>
              <h2>
                A different cover.
                <br />
                <em>The same place for you.</em>
              </h2>
            </div>
            <p>
              Good food, a fresh perspective, or a little clarity. Choose the
              everyday app you’d like to come back to.
            </p>
          </div>
          <div className="cover-grid">
            {covers.map((c, i) => {
              const Icon = coverIcons[c.id];
              return (
                <article className="cover-choice" key={c.id}>
                  <button
                    className="cover-preview-button"
                    onClick={() => go(coverPath(c.id))}
                    aria-label={`Preview ${c.name}`}
                  >
                    <CoverPreview cover={c.id} />
                    <span className="preview-corner">
                      <MoveUpRight size={22} />
                    </span>
                  </button>
                  <div className="cover-caption">
                    <span className="cover-number">0{i + 1}</span>
                    <div>
                      <h3>
                        <Icon size={17} aria-hidden="true" />
                        {c.name}
                      </h3>
                      <p>{c.caption}</p>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
        <section className="how-section page-width" id="how-it-works">
          <div className="how-intro">
            <span className="eyebrow">A SIMPLE START</span>
            <h2>
              Make yourself
              <br />
              <em>at home.</em>
            </h2>
            <Branch className="how-branch" />
          </div>
          <ol className="how-steps">
            {[
              [
                "Create your account",
                "A few details to get you settled. Your profile connects your experience.",
              ],
              [
                "Choose your everyday",
                "Pick a cover app that feels familiar. You can change it later in your settings.",
              ],
              [
                "Come back to your space",
                "Sign in and we’ll take you to the cover linked to your account.",
              ],
            ].map(([title, copy], i) => (
              <li key={title}>
                <span>0{i + 1}</span>
                <div>
                  <h3>{title}</h3>
                  <p>{copy}</p>
                </div>
                <Check size={17} />
              </li>
            ))}
          </ol>
        </section>
        <section className="landing-cta page-width">
          <Branch />
          <div>
            <span className="eyebrow">YOUR NEXT CHAPTER, AT YOUR PACE</span>
            <h2>
              A little space can
              <br />
              make a lot of difference.
            </h2>
          </div>
          <Button variant="light" onClick={() => go("/account/create")}>
            <Forward>Let’s begin</Forward>
          </Button>
        </section>
      </main>
      <footer className="site-footer page-width">
        <Logo small />
        <p>A familiar place. A little more room.</p>
        <a
          href={siteHref("/get")}
          className="text-link"
          onClick={(event) => {
            event.preventDefault();
            go("/get");
          }}
        >
          Download & installation
        </a>
        <span>Local preview · yourolivebranch.ca</span>
      </footer>
    </div>
  );
}
