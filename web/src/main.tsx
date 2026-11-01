// All app roles: starts the web app and chooses the page for this domain.
import {
  Component,
  lazy,
  Suspense,
  useEffect,
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
const Calculator = lazy(() => import("./public/covers/CalculatorCover"));
const AdminPortal = lazy(() => import("./users/admin/AdminDashboardPage"));
const StaffPortal = lazy(() => import("./users/staff/StaffDashboardPage"));
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
function Cover() {
  const s = useSession();
  return (
    <main
      id="main"
      className={`cover-container${s.cover === "GAME" ? " game-cover" : ""}`}
    >
      {s.cover === "FOOD" ? (
        <Food />
      ) : s.cover === "NEWS" ? (
        <News />
      ) : s.cover === "CALCULATOR" ? (
        <Calculator />
      ) : (
        <Game />
      )}
    </main>
  );
}
function Routes({ mode }: { mode: SiteMode }) {
  const s = useSession();
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
            : icon === "calculator"
              ? "Better Calculator"
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
