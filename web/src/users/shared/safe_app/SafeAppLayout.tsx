// All safe-app roles: routes signed-in users between safe-app pages.
import { lazy, Suspense, useEffect, useState, type ComponentType } from "react";
import {
  History as HistoryIcon,
  ArrowLeft,
  Menu,
  FileLock2,
  HeartHandshake,
  House,
  Leaf,
  LogOut,
  MessageCircle,
  MapPinned,
  NotebookPen,
  Settings,
  ShieldCheck,
  UserRound,
  Users,
  PhoneCall,
} from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Modal } from "../../../components/SharedComponents";
import type { Page } from "../../../domain/DomainModels";
import { accounts } from "../../../services/AccountService";
import { observePlan } from "../../../services/SafetyPlanService";
import { register } from "../../../services/MessagingService";
import Activity from "./ActivityHistoryPage";
import Home from "./SafeAppDashboard";
import Profile from "./AccountProfilePage";
import SettingsPage from "./AccountSettingsPage";
import Contacts from "../../user/contacts_and_notes/UserContactsPage";
import Notes from "../../user/contacts_and_notes/UserNotesPage";
import {
  DocumentsPage,
  HotlinesPage,
  LocationPage,
  StaffHelpPage,
} from "./SupportAndLocationPages";

const Messages = lazy(() => import("./FriendsAndMessagesPage"));
const SafetyPlan = lazy(() => import("./SafetyPlanPage"));
const nav: { page: Page; label: string; icon: typeof House; userOnly?: boolean }[] = [
  { page: "Home", label: "Home", icon: House },
  { page: "Notes", label: "Notes & incidents", icon: NotebookPen },
  { page: "Messages", label: "Friends & messages", icon: MessageCircle },
  { page: "SafetyPlan", label: "Safety plan", icon: ShieldCheck },
  { page: "Documents", label: "Documents", icon: FileLock2 },
  { page: "Location", label: "Location", icon: MapPinned },
  { page: "Hotlines", label: "Hotlines", icon: PhoneCall, userOnly: true },
  { page: "StaffHelp", label: "Staff help", icon: HeartHandshake, userOnly: true },
  { page: "Contacts", label: "Trusted contacts", icon: Users, userOnly: true },
  { page: "Profile", label: "My profile", icon: UserRound },
  { page: "Activity", label: "Account activity", icon: HistoryIcon },
  { page: "Settings", label: "Settings", icon: Settings },
];

const pages: Record<Page, ComponentType> = {
  Home, Notes, Contacts, Profile, Settings: SettingsPage, Activity,
  SafetyPlan, Documents: DocumentsPage, Location: LocationPage,
  Hotlines: HotlinesPage, StaffHelp: StaffHelpPage, Messages,
};

export default function SafeApp() {
  const s = useSession();
  const p = s.profile!;
  const identity = accounts.identity;
  const uid = identity?.uid;
  const [menuOpen, setMenuOpen] = useState(false);
  const [completedAt, setCompletedAt] = useState(0);
  const [dismissedAt, setDismissedAt] = useState(0);
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
  }, [s.page]);
  useEffect(() => {
    if (identity && s.cloudMode) void register(identity).catch(() => undefined);
  }, [uid, s.cloudMode]);
  useEffect(() => {
    if (!uid || p.role !== "USER" || !s.cloudMode) return;
    let active = true;
    let stop: (() => void) | undefined;
    void observePlan(uid, (plan) => {
      if (active) setCompletedAt(plan.consultationRequested ? 0 : plan.plansCompletedAt ?? 0);
    }, () => undefined).then((cleanup) => {
      if (active) stop = cleanup;
      else cleanup();
    }).catch(() => undefined);
    return () => { active = false; stop?.(); };
  }, [uid, p.role, s.cloudMode]);
  const CurrentPage = pages[s.page];
  const title = s.page === "Home"
    ? p.role === "SAFE_CONTACT" ? "Safe contact space" : "Your Safe Space"
    : nav.find(({ page }) => page === s.page)?.label;
  const navigate = (page: Page) => {
    setMenuOpen(false);
    s.clearMessage();
    s.setPage(page);
  };

  return (
    <div className="safe-shell private-shell">
      <aside className="safe-sidebar" data-menu-open={menuOpen}>
        <button className="safe-brand" type="button" onClick={() => navigate("Home")}>
          <Leaf size={24} aria-hidden="true" />
          <span>Olive Branch</span>
        </button>
        <button className="safe-menu-toggle" type="button" aria-label="Navigation"
          aria-expanded={menuOpen} aria-controls="safe-navigation" onClick={() => setMenuOpen((open) => !open)}>
          <Menu size={22} aria-hidden="true" />
        </button>
        <nav id="safe-navigation" aria-label="Safe Space navigation">
          {nav.filter(({ userOnly }) => !userOnly || p.role === "USER").map(({ page, label, icon: Icon }) => (
            <button key={page} type="button" aria-current={s.page === page ? "page" : undefined}
              onClick={() => navigate(page)}>
              <Icon size={20} aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <div className="safe-sidebar-bottom">
          <span className="avatar" aria-hidden="true">{p.username.charAt(0).toUpperCase()}</span>
          <strong>@{p.username}</strong>
        </div>
      </aside>
      <div className="safe-main">
        <header className="safe-header">
          <div className="safe-heading">
            {s.page !== "Home" && (
              <button type="button" className="safe-icon-button" aria-label="Back to Safe Space"
                onClick={() => navigate("Home")}><ArrowLeft size={22} aria-hidden="true" /></button>
            )}
            <h1>{title}</h1>
          </div>
          <div className="safe-header-actions">
            {s.page === "Home" && (
              <button type="button" className="safe-icon-button" aria-label="Open settings" onClick={() => navigate("Settings")}>
                <Settings size={21} aria-hidden="true" />
              </button>
            )}
            {p.role === "USER" && <Button variant="exit" onClick={s.quickExit}>Quick Exit <LogOut size={17} /></Button>}
          </div>
        </header>
        <main id="main" tabIndex={-1}>
          {s.page !== "SafetyPlan" && completedAt > dismissedAt && (
            <div className="status-banner" role="status">
              Your safety plan and backup plan are complete.
              <button className="text-link" onClick={() => setDismissedAt(completedAt)}>Dismiss</button>
            </div>
          )}
          {s.message && (
            <div className="status-banner" role="status">
              {s.message}
              <button className="text-link" onClick={s.clearMessage}>Dismiss</button>
            </div>
          )}
          <Suspense fallback={<p role="status">Opening {title?.toLowerCase()}…</p>}>
            <CurrentPage />
          </Suspense>
        </main>
        <footer className="safe-footer">
          <button className="text-link" onClick={() => void s.perform(s.signOut)}>Sign out</button>
        </footer>
      </div>
      {s.idleSeconds !== null && (
        <Modal title="Your Safe Space is about to lock" onClose={s.noteInteraction}>
          <p>Little Grove will reopen in {s.idleSeconds} seconds.</p>
          <Button onClick={s.noteInteraction}>Stay here</Button>
          {p.role === "USER" && <Button variant="quiet" onClick={s.quickExit}>Quick Exit</Button>}
        </Modal>
      )}
    </div>
  );
}
