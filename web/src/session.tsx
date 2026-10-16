import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  accounts,
  cloudMode,
  triggerHash,
  firebase,
} from "./services/accounts";
import { database } from "./services/local";
import {
  issueEntryLink,
  verifyEntryLink,
  validEntryToken,
} from "./services/entry-links";
import { readCover, writeCover } from "./services/cover-storage";
import { canonicalPath, coverForRoute, siteDestination } from "./sites";
import {
  coverPath,
  covers,
  emptyHistory,
  friendlyError,
  newEvent,
  type Cover,
  type Enrollment,
  type History,
  type Profile,
  type Role,
  type Page,
  type Entry,
  type Event,
  type Category,
} from "./domain";
type SessionValue = {
  path: string;
  go: (path: string) => void;
  phase: "public" | "setup" | "link" | "pin" | "safe" | "portal";
  personalLink: string;
  hasEntryToken: boolean;
  profile: Profile | null;
  history: History;
  enrollment: Enrollment | null;
  cover: Cover;
  page: Page;
  setPage: (p: Page) => void;
  busy: boolean;
  message: string;
  clearMessage: () => void;
  perform: (work: () => Promise<void>) => Promise<void>;
  authenticate: (
    username: string,
    password: string,
    role: Role,
    create: boolean,
  ) => Promise<void>;
  completeSetup: (p: Profile, pin: string) => Promise<void>;
  quickExit: () => void;
  searchTrigger: (value: string) => Promise<boolean>;
  unlock: (pin: string) => Promise<void>;
  saveProfile: (p: Profile, action?: string) => Promise<void>;
  saveEntry: (entry: Entry) => Promise<void>;
  record: (category: Category, event: Event | Entry) => Promise<void>;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  idleSeconds: number | null;
  noteInteraction: () => void;
  cloudMode: boolean;
  onboardingProfile: Profile | null;
};
const initialEntryToken =
  location.pathname === "/entry" && validEntryToken(location.hash.slice(1))
    ? location.hash.slice(1)
    : null;
if (location.pathname === "/entry" && location.hash)
  window.history.replaceState({}, "", "/entry");
const Context = createContext<SessionValue | null>(null);
export const useSession = () => {
  const value = useContext(Context);
  if (!value) throw new Error("Missing session");
  return value;
};
export function SessionProvider({ children }: { children: ReactNode }) {
  const [path, setPath] = useState(() => canonicalPath(location.pathname));
  const [phase, setPhase] = useState<SessionValue["phase"]>("public");
  const [personalLink, setPersonalLink] = useState("");
  const [entryAttempt, setEntryAttempt] = useState(0);
  const pendingEntry = useRef(initialEntryToken);
  const entryOwner = useRef<string | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [history, setHistory] = useState<History>(emptyHistory);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [page, setPage] = useState<Page>("Home");
  const [onboardingProfile, setOnboardingProfile] = useState<Profile | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [idleSeconds, setIdleSeconds] = useState<number | null>(null);
  const epoch = useRef(0);
  const lastInteraction = useRef(Date.now());
  const working = useRef(false);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  const routeCover = coverForRoute(path, location.origin)?.id as
    Cover | undefined;
  const cover = routeCover ?? enrollment?.cover ?? profile?.coverType ?? "FOOD";
  const go = useCallback((to: string) => {
    pendingEntry.current = null;
    entryOwner.current = null;
    const destination = new URL(siteDestination(to, location.origin));
    if (destination.origin !== location.origin) {
      accounts.lock();
      window.location.assign(destination.href);
      return;
    }
    const localPath =
      destination.pathname + destination.search + destination.hash;
    window.history.pushState({}, "", localPath);
    setPath(destination.pathname);
    window.scrollTo({ top: 0 });
  }, []);
  useEffect(() => {
    let live = true;
    void database
      .then((db) => db.get("device", "current"))
      .then(async (value) => {
        if (!live) return;
        setEnrollment(value ?? null);
        if (value && pendingEntry.current) {
          let authenticated = true;
          if (cloudMode) {
            const { auth } = await firebase();
            await auth.authStateReady();
            authenticated = auth.currentUser?.uid === value.uid;
          }
          const entryToken = pendingEntry.current;
          if (
            entryToken &&
            authenticated &&
            (await verifyEntryLink(value.uid, entryToken, !cloudMode)) &&
            live &&
            pendingEntry.current === entryToken
          ) {
            entryOwner.current = value.uid;
            setPhase("pin");
          }
        }
      })
      .catch(() =>
        setMessage(
          "Browser storage is unavailable. Enable site storage and try again.",
        ),
      );
    return () => {
      live = false;
    };
  }, [entryAttempt]);
  const quickExit = useCallback(() => {
    const destination = new URL(
      siteDestination(coverPath(profile?.coverType ?? cover), location.origin),
    );
    epoch.current++;
    entryOwner.current = null;
    pendingEntry.current = null;
    accounts.lock();
    setProfile(null);
    setOnboardingProfile(null);
    setPersonalLink("");
    setHistory(emptyHistory());
    setPage("Home");
    setMessage("");
    setIdleSeconds(null);
    setBusy(false);
    setPhase("public");
    phaseRef.current = "public";
    if (destination.origin !== location.origin) {
      window.location.replace(destination.href);
      return;
    }
    window.history.replaceState({}, "", destination.pathname);
    setPath(destination.pathname);
    window.scrollTo({ top: 0 });
  }, [cover, profile?.coverType]);
  useEffect(() => {
    const onPop = () => {
      epoch.current++;
      entryOwner.current = null;
      pendingEntry.current = null;
      accounts.lock();
      setProfile(null);
      setHistory(emptyHistory());
      setOnboardingProfile(null);
      setPersonalLink("");
      setIdleSeconds(null);
      setBusy(false);
      setPhase("public");
      setPath(canonicalPath(location.pathname));
      setMessage("");
    };
    addEventListener("popstate", onPop);
    const onHash = () => {
      if (location.pathname !== "/entry") return;
      const candidate = location.hash.slice(1);
      epoch.current++;
      accounts.lock();
      entryOwner.current = null;
      pendingEntry.current = validEntryToken(candidate) ? candidate : null;
      window.history.replaceState({}, "", "/entry");
      setPath("/entry");
      setProfile(null);
      setHistory(emptyHistory());
      setPersonalLink("");
      setOnboardingProfile(null);
      setMessage("");
      setBusy(false);
      setPhase("public");
      setEntryAttempt((n) => n + 1);
    };
    addEventListener("hashchange", onHash);
    return () => {
      removeEventListener("popstate", onPop);
      removeEventListener("hashchange", onHash);
    };
  }, []);
  useEffect(() => {
    const onHidden = () => {
      if (document.hidden && phaseRef.current !== "public") quickExit();
    };
    const onKey = (e: KeyboardEvent) => {
      if (
        e.key === "Escape" &&
        (phaseRef.current === "safe" ||
          phaseRef.current === "pin" ||
          phaseRef.current === "portal")
      ) {
        e.preventDefault();
        quickExit();
      }
    };
    document.addEventListener("visibilitychange", onHidden);
    const onPageHide = () => {
      if (phaseRef.current !== "public") quickExit();
      else {
        epoch.current++;
        accounts.lock();
      }
    };
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("visibilitychange", onHidden);
      window.removeEventListener("pagehide", onPageHide);
      window.removeEventListener("keydown", onKey);
    };
  }, [quickExit]);
  const noteInteraction = useCallback(() => {
    lastInteraction.current = Date.now();
    setIdleSeconds(null);
  }, []);
  useEffect(() => {
    if (phase !== "safe" && phase !== "portal") return;
    noteInteraction();
    // Modal autofocus and background DOM changes must not keep an idle account unlocked.
    const events = ["pointerdown", "keydown", "input"] as const;
    events.forEach((e) => document.addEventListener(e, noteInteraction));
    const interval = setInterval(() => {
      const elapsed = Date.now() - lastInteraction.current;
      if (elapsed >= 75_000) quickExit();
      else
        setIdleSeconds(
          elapsed >= 60_000 ? Math.ceil((75_000 - elapsed) / 1000) : null,
        );
    }, 500);
    return () => {
      clearInterval(interval);
      events.forEach((e) => document.removeEventListener(e, noteInteraction));
    };
  }, [phase, noteInteraction, quickExit]);
  useEffect(() => {
    document.title =
      phase === "safe" || phase === "pin"
        ? covers.find((c) => c.id === cover)!.name
        : coverForRoute(path, location.origin)
          ? covers.find((c) => c.id === cover)!.name
          : path.startsWith("/account") || path === "/download"
            ? "Olive Branch"
            : "Little Grove";
  }, [path, cover, phase]);
  async function perform(work: () => Promise<void>) {
    if (working.current) return;
    working.current = true;
    setBusy(true);
    setMessage("");
    const token = epoch.current;
    try {
      await work();
    } catch (error) {
      if (token === epoch.current) setMessage(friendlyError(error));
    } finally {
      working.current = false;
      if (token === epoch.current) {
        setBusy(false);
      }
    }
  }
  async function authenticate(
    username: string,
    password: string,
    role: Role,
    create: boolean,
  ) {
    const token = epoch.current;
    const loaded = await accounts.authenticate(
      username,
      password,
      role,
      create,
    );
    if (token !== epoch.current) {
      accounts.lock();
      return;
    }
    const p = loaded.profile;
    if (pendingEntry.current) {
      if (
        !(await verifyEntryLink(accounts.identity!.uid, pendingEntry.current))
      ) {
        accounts.lock();
        throw new Error(
          "This link is invalid, has been replaced, or belongs to another account.",
        );
      }
      entryOwner.current = accounts.identity!.uid;
    }
    if (!p || !p.fullName || !p.dob || (!p.triggerHash && role === "USER")) {
      setOnboardingProfile(p);
      setPhase("setup");
      return;
    }
    if (role === "STAFF" || role === "ADMIN") {
      setProfile(p);
      setHistory(loaded.history);
      setPhase("portal");
      return;
    }
    if (
      role === "SAFE_CONTACT" &&
      entryOwner.current === accounts.identity?.uid
    ) {
      setProfile(p);
      setHistory(loaded.history);
      setPhase("safe");
      return;
    }
    if (role === "SAFE_CONTACT" && path !== "/account/link") {
      accounts.lock();
      setPhase("public");
      go(coverPath(p.coverType));
      return;
    }
    const current: Enrollment | undefined = await (
      await database
    ).get("device", "current");
    if (path === "/account/link") {
      setPersonalLink(await issueEntryLink(accounts.identity!.uid));
      setEnrollment(
        current && current.uid === accounts.identity?.uid
          ? { ...current, cover: p.coverType }
          : null,
      );
      setPhase("link");
      return;
    }
    if (current && current.uid === accounts.identity?.uid) {
      setEnrollment({
        ...current,
        cover: p.coverType,
        triggerHash: p.triggerHash,
      });
      accounts.lock();
      if (entryOwner.current === current.uid) setPhase("pin");
      else {
        go(coverPath(p.coverType));
        setPhase("public");
      }
    } else {
      setOnboardingProfile(p);
      setPhase("setup");
    }
  }
  async function completeSetup(p: Profile, pin: string) {
    const token = epoch.current;
    await accounts.saveProfile(p);
    if (token !== epoch.current) return;
    if (p.role === "STAFF" || p.role === "ADMIN") {
      setProfile(p);
      setPhase("portal");
      return;
    }
    if (p.role === "SAFE_CONTACT") {
      setPersonalLink(await issueEntryLink(accounts.identity!.uid));
      setPhase("link");
      return;
    }
    const device = await accounts.enroll(p, pin);
    if (token !== epoch.current) return;
    setEnrollment(device);
    setOnboardingProfile(null);
    setProfile(null);
    if (entryOwner.current === device.uid) {
      accounts.lock();
      setPhase("pin");
      return;
    }
    setPersonalLink(await issueEntryLink(device.uid));
    setPhase("link");
  }
  async function searchTrigger(value: string) {
    if (
      !enrollment ||
      enrollment.cover !== cover ||
      enrollment.role !== "USER" ||
      entryOwner.current !== enrollment.uid
    )
      return false;
    if ((await triggerHash(value)) !== enrollment.triggerHash) return false;
    setMessage("");
    setPhase("pin");
    return true;
  }
  async function unlock(pin: string) {
    const token = epoch.current;
    if (
      !enrollment ||
      entryOwner.current !== enrollment.uid ||
      !pendingEntry.current ||
      !(await verifyEntryLink(enrollment.uid, pendingEntry.current, !cloudMode))
    )
      throw new Error("Open your current personal link before unlocking.");
    if (token !== epoch.current) return;
    const loaded = await accounts.unlock(pin);
    if (token !== epoch.current) {
      accounts.lock();
      return;
    }
    const event: Event = {
      id: crypto.randomUUID(),
      timestamp: Date.now(),
      source: "Web cover",
      pinAttempts: 1,
      lastResult: "Accepted",
    };
    await accounts.saveRecord("triggers", event);
    if (token !== epoch.current) return;
    const pending = await readCover<Event[]>(`${enrollment.uid}:checkouts`, []);
    for (const checkout of pending)
      await accounts.saveRecord("checkouts", checkout);
    await writeCover(`${enrollment.uid}:checkouts`, []);
    if (token !== epoch.current) return;
    setProfile(loaded.profile);
    setHistory({
      ...loaded.history,
      triggers: [event, ...loaded.history.triggers],
      checkouts: [...pending, ...loaded.history.checkouts],
    });
    setPhase("safe");
    setPage("Home");
    noteInteraction();
  }
  async function record(category: Category, value: Event | Entry) {
    const token = epoch.current;
    await accounts.saveRecord(category, value);
    if (token !== epoch.current) return;
    setHistory((old) => ({
      ...old,
      [category]: [value, ...old[category].filter((e) => e.id !== value.id)],
    }));
  }
  async function saveProfile(p: Profile, action = "Saved profile settings") {
    const token = epoch.current;
    await accounts.saveProfile(p);
    if (token !== epoch.current) return;
    setProfile(p);
    if (enrollment)
      setEnrollment({
        ...enrollment,
        cover: p.coverType,
        triggerHash: p.triggerHash,
      });
    await record("settings", newEvent(action));
    setMessage("Changes saved.");
  }
  async function saveEntry(entry: Entry) {
    await record("private_entries", entry);
    setMessage("Entry saved.");
  }
  async function refresh() {
    const token = epoch.current;
    const loaded = await accounts.load();
    if (token === epoch.current) {
      setProfile(loaded.profile);
      setHistory(loaded.history);
      setMessage("Account is up to date.");
    }
  }
  async function signOut() {
    await accounts.signOut();
    epoch.current++;
    setProfile(null);
    setHistory(emptyHistory());
    setEnrollment(null);
    await (await database).delete("device", "current");
    setPhase("public");
    go("/");
  }
  return (
    <Context.Provider
      value={{
        path,
        go,
        phase,
        personalLink,
        hasEntryToken: Boolean(pendingEntry.current),
        profile,
        history,
        enrollment,
        cover,
        page,
        setPage,
        busy,
        message,
        clearMessage: () => setMessage(""),
        perform,
        authenticate,
        completeSetup,
        quickExit,
        searchTrigger,
        unlock,
        saveProfile,
        saveEntry,
        record,
        refresh,
        signOut,
        idleSeconds,
        noteInteraction,
        cloudMode,
        onboardingProfile,
      }}
    >
      {children}
    </Context.Provider>
  );
}
