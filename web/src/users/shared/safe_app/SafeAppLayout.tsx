// All safe-app roles: routes signed-in users between safe-app pages.
import { lazy, Suspense, useEffect, useRef, useState, type ComponentType } from "react";
import {
  History as HistoryIcon,
  ArrowLeft,
  Menu,
  X,
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
  LocationPage,
  StaffHelpPage,
} from "./SupportAndLocationPages";

const Messages = lazy(() => import("./FriendsAndMessagesPage"));
const SafetyPlan = lazy(() => import("./SafetyPlanPage"));
const HotlinesPage = lazy(() => import("./HotlinesPage"));
type NavItem = { page: Page; label: string; icon: typeof House; userOnly?: boolean };
const navigation: { label: string; items: NavItem[] }[] = [
  { label: "Workspace", items: [
    { page: "Home", label: "Home", icon: House },
    { page: "SafetyPlan", label: "Safety plan", icon: ShieldCheck },
    { page: "Notes", label: "Notes & incidents", icon: NotebookPen },
    { page: "Documents", label: "Documents", icon: FileLock2 },
  ] },
  { label: "Connections", items: [
    { page: "Messages", label: "Friends & messages", icon: MessageCircle },
    { page: "Contacts", label: "Trusted contacts", icon: Users, userOnly: true },
    { page: "Location", label: "Location", icon: MapPinned },
    { page: "StaffHelp", label: "Staff help", icon: HeartHandshake, userOnly: true },
    { page: "Hotlines", label: "Hotlines", icon: PhoneCall, userOnly: true },
  ] },
  { label: "Account", items: [
    { page: "Profile", label: "My profile", icon: UserRound },
    { page: "Activity", label: "Account activity", icon: HistoryIcon },
    { page: "Settings", label: "Settings", icon: Settings },
  ] },
];
const contactNavigation = [
  { label: "Shared with you", items: [
    { page: "Home", label: "Home", icon: House },
    { page: "SafetyPlan", label: "Shared safety plans", icon: ShieldCheck },
    { page: "Messages", label: "Friends & messages", icon: MessageCircle },
    { page: "Documents", label: "Documents", icon: FileLock2 },
  ] },
  { label: "Personal tools", items: [
    { page: "Notes", label: "Notes & incidents", icon: NotebookPen },
    { page: "Location", label: "Location", icon: MapPinned },
  ] },
  navigation[2],
] satisfies typeof navigation;

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
  const heading = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(s.page);
  const [menuOpen, setMenuOpen] = useState(false);
  const [completedAt, setCompletedAt] = useState(0);
  const [dismissedAt, setDismissedAt] = useState(0);
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
    if (previousPage.current !== s.page) heading.current?.focus({ preventScroll: true });
    previousPage.current = s.page;
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
  const isSafeContact = p.role === "SAFE_CONTACT";
  const groups = isSafeContact ? contactNavigation : navigation;
  const CurrentPage = pages[s.page];
  const title = s.page === "Home"
    ? isSafeContact ? "Safe contact space" : "Your Safe Space"
    : groups.flatMap(({ items }) => items).find(({ page }) => page === s.page)?.label;
  const navigate = (page: Page) => {
    setMenuOpen(false);
    s.clearMessage();
    if (s.page === page) heading.current?.focus();
    s.setPage(page);
  };

  return (
    <div className="safe-shell private-shell">
      <aside className="safe-sidebar" data-menu-open={menuOpen}>
        <button className="safe-brand" type="button" onClick={() => navigate("Home")}>
          <span className="safe-brand-mark"><Leaf size={21} aria-hidden="true" /></span>
          <span>Olive Branch <small>Safe Space</small></span>
        </button>
        <button className="safe-menu-toggle" type="button" aria-label="Navigation"
          aria-expanded={menuOpen} aria-controls="safe-navigation" onClick={() => setMenuOpen((open) => !open)}>
          {menuOpen ? <X size={22} aria-hidden="true" /> : <Menu size={22} aria-hidden="true" />}
        </button>
        <nav id="safe-navigation" aria-label="Safe Space navigation">
          {groups.map(({ label, items }) => (
            <div className="safe-nav-group" key={label}>
              <p>{label}</p>
              {items.filter(({ userOnly }) => !userOnly || p.role === "USER").map(({ page, label: itemLabel, icon: Icon }) => (
                <button key={page} type="button" aria-current={s.page === page ? "page" : undefined}
                  onClick={() => navigate(page)}>
                  <Icon size={18} aria-hidden="true" />
                  <span>{itemLabel}</span>
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="safe-sidebar-bottom">
          <span className="avatar" aria-hidden="true">{p.username.charAt(0).toUpperCase()}</span>
          <div><strong>@{p.username}</strong><small>Signed in</small></div>
        </div>
      </aside>
      <div className="safe-main">
        <header className="safe-header">
          <div className="safe-heading">
            {s.page !== "Home" && (
              <button type="button" className="safe-icon-button" aria-label="Back to Safe Space"
                onClick={() => navigate("Home")}><ArrowLeft size={22} aria-hidden="true" /></button>
            )}
            <h1 tabIndex={-1} ref={heading}>{title}</h1>
          </div>
          <div className="safe-header-actions">
            {s.page === "Home" && (
              <button type="button" className="safe-icon-button" aria-label="Open settings" onClick={() => navigate("Settings")}>
                <Settings size={21} aria-hidden="true" />
              </button>
            )}
            {p.role === "USER" && <Button variant="exit" onClick={s.quickExit}><LogOut size={17} aria-hidden="true" /> Quick Exit</Button>}
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
