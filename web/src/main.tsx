// All app roles: starts the web app and chooses the page for this domain.
import {
  Component,
  lazy,
  Suspense,
  useState,
  type ErrorInfo,
  type ReactNode,
} from "react";
import { createRoot } from "react-dom/client";
import { Leaf } from "lucide-react";
import { SessionProvider, useSession } from "./session/SessionProvider";
import { AuthPage, Setup } from "./users/shared/auth/AccountEntryPage";
import { Button, Field } from "./components/SharedComponents";
import BrowserPinGate from "./users/shared/auth/BrowserPinGate";
import PublicCoverPage from "./public/PublicCoverPage";
import { siteMode, type SiteMode } from "./sites/SiteRouting";
import "./styles.css";
const Download = lazy(() => import("./public/AppDownloadInstructions"));
const EntryLink = lazy(() => import("./users/shared/auth/PrivateEntryLink"));
const SafeApp = lazy(() => import("./users/shared/safe_app/SafeAppLayout"));
const AdminPortal = lazy(() => import("./users/admin/AdminDashboardPage"));
const StaffPortal = lazy(() => import("./users/staff/StaffDashboardPage"));
// Little Grove is the browser cover for every account.
function Cover() {
  const s = useSession();
  return <PublicCoverPage onPrivateEntry={() => { void s.perform(s.beginEntry); }} />;
}

// All roles use the same zero-knowledge, tab-scoped recovery action.
function EncryptedAccessRecovery() {
  const s = useSession();
  const [password, setPassword] = useState("");
  if (
    !s.cloudMode ||
    (s.phase !== "safe" && s.phase !== "portal") ||
    s.hasEncryptedAccess ||
    s.passwordRecoveryEnrolled !== true
  ) return null;
  return (
    <section className="account-recovery-notice" aria-labelledby="encrypted-access-heading">
      <div>
        <h2 id="encrypted-access-heading">Unlock encrypted access</h2>
        <p>Enter your Olive Branch recovery password to open encrypted documents and messages on this browser.</p>
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          const entered = password;
          setPassword("");
          void s.perform(() => s.recoverEncryptedAccess(entered));
        }}
      >
        <Field
          label="Recovery password"
          type="password"
          autoComplete="off"
          value={password}
          disabled={s.busy}
          onChange={(event: { target: { value: string } }) => setPassword(event.target.value)}
        />
        <Button type="submit" disabled={s.busy || !password}>
          {s.busy ? "Unlocking…" : "Unlock with password"}
        </Button>
      </form>
    </section>
  );
}
// Sends each domain and signed-in role to its matching web screen.
function Routes({ mode }: { mode: SiteMode }) {
  const s = useSession();
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
  else if (s.phase === "pin") content = <BrowserPinGate />;
  else if (s.path === "/get") content = <Download />;
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
        <EncryptedAccessRecovery />
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
// An advertising document has no SessionProvider, PIN form, account form, or private workspace.
const publicAdvertisingDocument = document.documentElement.dataset.olivePublicAds === "true";
if (document.documentElement.dataset.olivePrivateRedirect === "true") {
  createRoot(document.getElementById("root")!).render(<main id="main" role="status">Opening…</main>);
} else if (publicAdvertisingDocument) {
  createRoot(document.getElementById("root")!).render(
    <ErrorBoundary>
      <a className="skip-link" href="#main">Skip to content</a>
      <Suspense fallback={<main id="main" role="status">Loading…</main>}>
        {mode === "download" ? <Download /> :
          <PublicCoverPage onPrivateEntry={() => window.location.assign("/entry#open")} />}
      </Suspense>
    </ErrorBoundary>,
  );
} else
// Hosted private sites must use shared Firebase accounts; local browser accounts cannot sync to phones.
if (
  import.meta.env.PROD &&
  mode !== "download" &&
  hostedSetupProblems.length > 0
) {
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
  // Private entry needs the browser's built-in encryption tools.
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
