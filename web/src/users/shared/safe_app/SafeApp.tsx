// Contains the safe app code for this part of Olive.
import { lazy, Suspense } from "react";
import {
  History as HistoryIcon,
  House,
  Leaf,
  LogOut,
  MessageCircle,
  NotebookPen,
  Settings,
  ShieldCheck,
  UserRound,
  Users,
} from "lucide-react";
import { useSession } from "../../../session";
import { Button, Modal } from "../../../components";
import type { Page } from "../../../domain";
import Activity from "./Activity";
import Home from "./Home";
import Profile from "./Profile";
import SettingsPage from "./Settings";
import Contacts from "../../user/safe_app/Contacts";
import Notes from "../../user/safe_app/Notes";

const Messages = lazy(() => import("./Messages"));
const SafetyPlan = lazy(() => import("./SafetyPlan"));
const nav: { page: Page; icon: typeof House }[] = [
  { page: "Home", icon: House },
  { page: "Notes", icon: NotebookPen },
  { page: "Messages", icon: MessageCircle },
  { page: "SafetyPlan", icon: ShieldCheck },
  { page: "Contacts", icon: Users },
  { page: "Profile", icon: UserRound },
  { page: "Settings", icon: Settings },
  { page: "Activity", icon: HistoryIcon },
];
export default function SafeApp() {
  const s = useSession();
  const p = s.profile!;
  return (
    <div className="safe-shell private-shell">
      <aside className="safe-sidebar">
        <a href="#main" className="safe-brand">
          <Leaf strokeWidth={1.2} />
          <span>
            A little space.<small>YOUR OLIVE BRANCH</small>
          </span>
        </a>
        <nav aria-label="Personal space navigation">
          {nav.map(({ page, icon: Icon }) => (
            <button
              key={page}
              aria-current={s.page === page ? "page" : undefined}
              onClick={() => {
                s.clearMessage();
                s.setPage(page);
              }}
            >
              <Icon size={20} />
              <span>{page}</span>
              {s.page === page && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="safe-sidebar-bottom">
          <span className="avatar">{p.username[0].toUpperCase()}</span>
          <div>
            <strong>@{p.username}</strong>
            <small>
              {s.cloudMode ? "Shared account" : "Browser-only account"}
            </small>
          </div>
        </div>
      </aside>
      <div className="safe-main">
        <header className="safe-header">
          <span className="eyebrow">YOUR SPACE, AT YOUR PACE</span>
          <Button variant="exit" onClick={s.quickExit}>
            Quick Exit <LogOut size={17} />
          </Button>
        </header>
        <main id="main" tabIndex={-1}>
          <div className="safe-page-title">
            <div>
              <span className="eyebrow">A PLACE THAT’S YOURS</span>
              <h1>
                {s.page === "Home"
                  ? `Welcome, ${p.fullName.split(" ")[0] || p.username}.`
                  : s.page === "Notes"
                    ? "A place for your thoughts."
                    : s.page === "Contacts"
                      ? "People in your corner."
                      : s.page === "Messages"
                        ? "Stay connected."
                        : s.page === "SafetyPlan"
                          ? "A plan shaped around you."
                          : s.page === "Settings"
                            ? "Your space. Your settings."
                            : s.page === "Activity"
                              ? "Your account, in perspective."
                              : "Your story, your words."}
              </h1>
            </div>
            <small className="today">
              {new Date().toLocaleDateString([], {
                weekday: "long",
                month: "long",
                day: "numeric",
              })}
            </small>
          </div>
          {s.message && (
            <div className="status-banner" role="status">
              {s.message}
              <button className="text-link" onClick={s.clearMessage}>
                Dismiss
              </button>
            </div>
          )}
          {s.page === "Home" ? (
            <Home />
          ) : s.page === "Notes" ? (
            <Notes />
          ) : s.page === "Contacts" ? (
            <Contacts />
          ) : s.page === "Profile" ? (
            <Profile />
          ) : s.page === "Settings" ? (
            <SettingsPage />
          ) : s.page === "Activity" ? (
            <Activity />
          ) : s.page === "SafetyPlan" ? (
            <Suspense fallback={<p role="status">Opening safety plan…</p>}>
              <SafetyPlan />
            </Suspense>
          ) : (
            <Suspense fallback={<p role="status">Opening conversations…</p>}>
              <Messages />
            </Suspense>
          )}
        </main>
        <footer className="safe-footer">
          <span>A small step is still a step.</span>
          <button
            className="text-link"
            onClick={() => void s.perform(s.signOut)}
          >
            Sign out
          </button>
        </footer>
      </div>
      {s.idleSeconds !== null && (
        <Modal
          title="Would you like a little more time?"
          onClose={s.noteInteraction}
        >
          <p>
            Your space locks after inactivity. Returning to your cover in{" "}
            {s.idleSeconds} seconds.
          </p>
          <Button onClick={s.noteInteraction}>Stay here</Button>
          <Button variant="quiet" onClick={s.quickExit}>
            Quick Exit
          </Button>
        </Modal>
      )}
    </div>
  );
}
