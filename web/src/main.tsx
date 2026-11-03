// All app roles: starts the web app and chooses the page for this domain.
import {
  Component,
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
  type ErrorInfo,
  type ReactNode,
} from "react";
import { createRoot } from "react-dom/client";
import { LogOut, Delete, ArrowLeft, Leaf } from "lucide-react";
import { SessionProvider, useSession } from "./session/SessionProvider";
import { AuthPage, Setup } from "./users/shared/auth/AccountEntryPage";
import { Button, Field, Form } from "./components/SharedComponents";
import Game from "./public/covers/OliveGamesCover";
import { coverSite, siteMode, type SiteMode } from "./sites/SiteRouting";
import "./styles.css";
const Download = lazy(() => import("./public/AppDownloadInstructions"));
const EntryLink = lazy(() => import("./users/shared/auth/PrivateEntryLink"));
const SafeApp = lazy(() => import("./users/shared/safe_app/SafeAppLayout"));
const Food = lazy(() => import("./public/covers/FoodDeliveryCover"));
const News = lazy(() => import("./public/covers/DailyDigestCover"));
const Fitness = lazy(() => import("./public/covers/FitnessCover"));
const AdminPortal = lazy(() => import("./users/admin/AdminDashboardPage"));
const StaffPortal = lazy(() => import("./users/staff/StaffDashboardPage"));
// Web main: Shows the pin gate view.
function PinGate() {
  const s = useSession();
  const [pin, setPin] = useState("");
  return (
    <main id="main" className="pin-page private-shell">
      <div className="pin-top">
        <span>
          <Leaf size={20} /> A little space.
        </span>
        <Button variant="exit" onClick={s.quickExit}>
          Quick Exit <LogOut size={17} />
        </Button>
      </div>
      <div className="pin-card">
        <span className="eyebrow">YOUR PERSONAL ENTRY</span>
        <h1>
          A familiar
          <br />
          <em>way back.</em>
        </h1>
        <p>Enter your browser PIN to open your space.</p>
        <Form
          onSubmit={async () => {
            await s.perform(async () => {
              try {
                await s.unlock(pin);
              } finally {
                setPin("");
              }
            });
          }}
        >
          <Field
            label="Browser PIN"
            type="password"
            value={pin}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setPin(e.target.value.replace(/\D/g, "").slice(0, 6))
            }
            inputMode="numeric"
            autoComplete="off"
            pattern="[0-9]{4,6}"
            required
            maxLength={6}
          />
          <div
            className="pin-count"
            aria-live="polite"
            aria-label={`Digits entered: ${pin.length}`}
          >
            {Array.from({ length: 6 }, (_, i) => (
              <i key={i} className={i < pin.length ? "entered" : ""} />
            ))}
          </div>
          <div className="pin-keypad">
            {[
              "1",
              "2",
              "3",
              "4",
              "5",
              "6",
              "7",
              "8",
              "9",
              "delete",
              "0",
              "unlock",
            ].map((key) => (
              <button
                type={key === "unlock" ? "submit" : "button"}
                key={key}
                aria-label={
                  key === "delete"
                    ? "Delete digit"
                    : key === "unlock"
                      ? "Unlock"
                      : key
                }
                disabled={s.busy || (key === "unlock" && pin.length < 4)}
                onClick={() => {
                  if (key === "delete") setPin((v) => v.slice(0, -1));
                  else if (key !== "unlock")
                    setPin((v) => (v + key).slice(0, 6));
                }}
              >
                {key === "delete" ? (
                  <Delete size={22} />
                ) : key === "unlock" ? (
                  <LogOut size={22} />
                ) : (
                  key
                )}
              </button>
            ))}
          </div>
          {s.message && (
            <p role="alert" className="form-error">
              {s.message}
            </p>
          )}
        </Form>
        <button
          className="text-link"
          onClick={() => {
            s.quickExit();
            s.go("/account/link");
          }}
        >
          Need a new browser PIN or link? <ArrowLeft size={15} />
        </button>
      </div>
    </main>
  );
}
// Web main: Shows the cover view.
function Cover() {
  const s = useSession();
  const [accountOpen, setAccountOpen] = useState(false);
  const lastOliveTap = useRef(0);
  const coverLabel = s.cover === "FOOD"
    ? "Olive"
    : s.cover === "NEWS"
      ? "Daily Digest"
      : s.cover === "FITNESS"
        ? "Fitness"
        : "Game";
  const tapOlive = () => {
    const now = Date.now();
    if (now - lastOliveTap.current > 1500) {
      lastOliveTap.current = now;
      return;
    }
    lastOliveTap.current = 0;
    void s.perform(s.beginEntry);
  };
  return (
    <main
      id="main"
      className={`cover-container ${s.cover.toLowerCase()}-cover`}
    >
      <nav className="cover-account-tabs" role="tablist" aria-label={`${coverLabel} app pages`}>
        <button type="button" role="tab" aria-selected={!accountOpen} aria-controls="cover-display-panel" onClick={() => setAccountOpen(false)}>
          {coverLabel}
        </button>
        <button type="button" role="tab" aria-selected={accountOpen} aria-controls="cover-account-panel" onClick={() => setAccountOpen(true)}>
          Account
        </button>
      </nav>
      <div id="cover-display-panel" className="cover-tab-panel" role="tabpanel" hidden={accountOpen}>
        {s.cover === "FOOD" ? (
          <Food />
        ) : s.cover === "NEWS" ? (
          <News />
        ) : s.cover === "FITNESS" ? (
          <Fitness />
        ) : (
          <Game />
        )}
      </div>
      <section id="cover-account-panel" className="cover-account-panel" role="tabpanel" aria-label="Account" hidden={!accountOpen}>
          <span className="eyebrow">ACCOUNT</span>
          <h1>Your account.</h1>
          <p>Account access and private settings.</p>
          <button type="button" className="cover-hidden-olive" aria-label="olive icon" onClick={tapOlive}>
            <Leaf aria-hidden="true" />
          </button>
      </section>
    </main>
  );
}
// Web main: Shows the routes view.
function Routes({ mode }: { mode: SiteMode }) {
  const s = useSession();
  // Update s when information used by this screen changes.
  useEffect(() => {
    const cover = coverSite(s.cover).slug;
    let link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]');
    if (!link) {
      link = document.createElement("link");
      link.rel = "manifest";
      document.head.append(link);
    }
    link.href = `/${cover}/manifest.webmanifest`;
    const icon = cover;
    const apple = document.querySelector<HTMLLinkElement>(
      'link[rel="apple-touch-icon"]',
    );
    if (apple) apple.href = `/icons/${icon}-192.png`;
    const favicon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (favicon) favicon.href = `/icons/${icon}.svg`;
    const appleName = document.querySelector<HTMLMetaElement>(
      'meta[name="apple-mobile-web-app-title"]',
    );
    if (appleName)
      appleName.content =
        icon === "olive"
          ? "Olive"
          : icon === "daily-digest"
            ? "Daily Digest"
            : icon === "fitness"
              ? "Fitness Tracker"
              : "Little Grove";
  }, [s.cover]);
  let content: ReactNode;
  if (mode === "download") content = <Download />;
  else if (mode === "admin") {
    if (s.phase === "portal" && s.profile)
      content = s.profile.role === "ADMIN" ? <AdminPortal /> : <StaffPortal />;
    else if (s.phase === "setup") content = <Setup />;
    else content = <AuthPage organizationOnly />;
  } else if (s.phase === "safe" && s.profile) content = <SafeApp />;
  else if (s.phase === "setup") content = <Setup />;
  else if (s.phase === "link") content = <EntryLink />;
  else if (s.phase === "pin") content = <PinGate />;
  else if (s.path === "/download") content = <Download />;
  else if (s.path.startsWith("/account")) content = <AuthPage />;
  else content = <Cover />;
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Suspense
        fallback={
          <div className="app-loading" role="status">
            <Leaf />
            <p>A little moment…</p>
          </div>
        }
      >
        {content}
      </Suspense>
    </>
  );
}
class ErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(_error: Error, _info: ErrorInfo) {
    /* Do not write private account data or component state to logs. */
  }
  render() {
    return this.state.failed ? (
      <main className="error-page">
        <h1>A little pause.</h1>
        <p>
          This page could not open. Your stored account has not been deleted.
        </p>
        <a href="/">Return to Little Grove</a>
      </main>
    ) : (
      this.props.children
    );
  }
}
const mode = siteMode();
const hostedSetupProblems = [
  !import.meta.env.VITE_FIREBASE_API_KEY && "VITE_FIREBASE_API_KEY",
  !import.meta.env.VITE_FIREBASE_AUTH_DOMAIN && "VITE_FIREBASE_AUTH_DOMAIN",
  !import.meta.env.VITE_FIREBASE_PROJECT_ID && "VITE_FIREBASE_PROJECT_ID",
  !import.meta.env.VITE_FIREBASE_STORAGE_BUCKET &&
    "VITE_FIREBASE_STORAGE_BUCKET",
  !import.meta.env.VITE_FIREBASE_APP_ID && "VITE_FIREBASE_APP_ID",
  !import.meta.env.VITE_FIREBASE_APPCHECK_SITE_KEY &&
    "VITE_FIREBASE_APPCHECK_SITE_KEY",
  import.meta.env.VITE_USE_FIREBASE_EMULATORS === "true" &&
    "VITE_USE_FIREBASE_EMULATORS must be false",
].filter(Boolean);
// Hosted private sites must use shared Firebase accounts; local browser accounts cannot sync to phones.
if (import.meta.env.PROD && mode !== "download" && hostedSetupProblems.length > 0) {
  createRoot(document.getElementById("root")!).render(
    <main className="error-page" id="main">
      <h1>Account service needs setup.</h1>
      <p>
        This site is not connected to shared accounts yet. Add the Firebase web
        settings and the App Check site key in Hostinger, then redeploy so this
        website and the phone app use the same accounts.
      </p>
      <p className="help-note">
        This build cannot see: {hostedSetupProblems.join(", ")}. Set these in
        this Hostinger app and rebuild it. VITE settings are read during the
        build.
      </p>
    </main>,
  );
} else
  // mode checks whether crypto subtle is false before continuing.
  if (!crypto.subtle) {
    createRoot(document.getElementById("root")!).render(
      <main className="error-page">
        <h1>A secure connection is needed.</h1>
        <p>
          Open this app over HTTPS, or use localhost on this computer. Browser
          encryption is unavailable on an ordinary LAN HTTP address.
        </p>
      </main>,
    );
  } else
    createRoot(document.getElementById("root")!).render(
      <ErrorBoundary>
        <SessionProvider mode={mode}>
          <Routes mode={mode} />
        </SessionProvider>
      </ErrorBoundary>,
    );
if ("serviceWorker" in navigator && import.meta.env.PROD)
  void navigator.serviceWorker.register("/sw.js").catch(() => {});
