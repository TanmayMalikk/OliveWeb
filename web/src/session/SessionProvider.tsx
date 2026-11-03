// Signed-in users: loads the account and keeps web session state in sync.
import {
  useCallback,
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
  observeAccountChanges,
} from "../services/AccountService";
import { database } from "../services/LocalDataStore";
import {
  issueEntryLink,
  savedEntryLink,
  verifyEntryLink,
  validEntryToken,
} from "../services/PrivateEntryLinks";
import { readCover, writeCover } from "../services/CoverStateStore";
import {
  canonicalPath,
  coverForRoute,
  siteDestination,
  type SiteMode,
} from "../sites/SiteRouting";
import {
  coverPath,
  covers,
  emptyHistory,
  friendlyError,
  newEvent,
  normalizeCover,
  type Cover,
  type Enrollment,
  type History,
  type Profile,
  type Role,
  type Page,
  type Entry,
  type Event,
  type Category,
} from "../domain/DomainModels";
import { SessionContext, useSession, type SessionValue } from "./SessionContext";
import { useSessionSecurity } from "./useSessionSecurity";
export { useSession };
function accountSyncError(error: unknown) {
  const code = (error as { code?: string })?.code;
  if (code === "permission-denied" || code === "firestore/permission-denied")
    return "You are signed in, but Firebase is blocking live account sync. Deploy the current Firestore rules to enable it.";
  return error instanceof Error ? error.message : "Account sync is unavailable.";
}
const initialEntryToken =
  (location.pathname === "/" || location.pathname === "/entry") &&
  validEntryToken(location.hash.slice(1))
    ? location.hash.slice(1)
    : null;
if (location.hash || location.pathname === "/entry")
  window.history.replaceState({}, "", "/");
// Web session provider: Shows the session provider view.
export function SessionProvider({
  children,
  mode = "user",
}: {
  children: ReactNode;
  mode?: SiteMode;
}) {
  const [path, setPath] = useState(() =>
    mode === "admin"
      ? "/account/sign-in"
      : mode === "download"
        ? "/download"
        : initialEntryToken
          ? "/account/sign-in"
          : canonicalPath(location.pathname),
  );
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
  const working = useRef(false);
  const routeCover = coverForRoute(path, location.origin)?.id as
    Cover | undefined;
  const cover = normalizeCover(
    routeCover ?? enrollment?.cover ?? profile?.coverType ?? "GAME",
  );
  // Reloads account files when another phone or browser changes this account.
  useEffect(() => {
    const identity = accounts.identity;
    if (!cloudMode || !identity || (phase !== "safe" && phase !== "portal"))
      return;
    let active = true;
    let timer = 0;
    let stop: (() => void) | undefined;
    void observeAccountChanges(
      identity.uid,
      () => {
        window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          void accounts.load().then((loaded) => {
            if (!active || accounts.identity?.uid !== identity.uid) return;
            setProfile(loaded.profile);
            setHistory(loaded.history);
          }).catch((error) => {
            if (active) setMessage(accountSyncError(error));
          });
        }, 150);
      },
      (error) => {
        if (active) setMessage(accountSyncError(error));
      },
    ).then((cleanup) => {
      if (active) stop = cleanup;
      else cleanup();
    }).catch((error) => {
      if (active) setMessage(accountSyncError(error));
    });
    return () => {
      active = false;
      window.clearTimeout(timer);
      stop?.();
    };
  }, [phase, accounts.identity?.uid]);
  const go = useCallback(
    (to: string) => {
      pendingEntry.current = null;
      entryOwner.current = null;
      const destination = new URL(siteDestination(to, location.origin));
      const internalAdmin = mode === "admin" && to !== "/download";
      const internalDownload = mode === "download" && to === "/download";
      if (
        !internalAdmin &&
        !internalDownload &&
        destination.origin !== location.origin
      ) {
        accounts.lock();
        window.location.assign(destination.href);
        return;
      }
      window.history.replaceState({}, "", "/");
      setPath(canonicalPath(to));
      window.scrollTo({ top: 0 });
    },
    [mode],
  );
  useEffect(() => {
    if (location.pathname !== "/" || location.search || location.hash)
      window.history.replaceState({}, "", "/");
  }, []);
  // Update internal download when information used by this screen changes.
  useEffect(() => {
    let live = true;
    void database
      .then((db) => db.get("device", "current"))
      .then(async (value) => {
        if (!live) return;
        const savedEnrollment = value
          ? { ...value, cover: normalizeCover(value.cover) }
          : null;
        setEnrollment(savedEnrollment);
        if (savedEnrollment && savedEnrollment.cover !== value?.cover)
          await (await database).put("device", savedEnrollment, "current");
        // saved enrollment checks whether value and pending entry current before continuing.
        if (value && pendingEntry.current) {
          let authenticated = true;
          if (cloudMode) {
            const { auth } = await firebase();
            await auth.authStateReady();
          authenticated = auth.currentUser?.uid === savedEnrollment?.uid;
          }
          const entryToken = pendingEntry.current;
          if (
            entryToken &&
            authenticated &&
            live &&
            pendingEntry.current === entryToken
          ) {
            entryOwner.current = savedEnrollment.uid;
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
    const returnPath = profile?.role === "SAFE_CONTACT"
      ? "/account/sign-in"
      : mode === "admin"
        ? "/account/sign-in"
        : mode === "download"
          ? "/download"
          : coverPath(profile?.coverType ?? cover);
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
    window.history.replaceState({}, "", "/");
    setPath(returnPath);
    window.scrollTo({ top: 0 });
  }, [cover, mode, profile?.coverType, profile?.role]);
  const noteInteraction = useSessionSecurity({
    enrollment,
    entryOwner,
    epoch,
    mode,
    pendingEntry,
    phase,
    role: profile?.role ?? null,
    quickExit,
    setBusy,
    setEnrollmentAttempt: setEntryAttempt,
    setHistory,
    setIdleSeconds,
    setMessage,
    setOnboardingProfile,
    setPath,
    setPersonalLink,
    setPhase,
    setProfile,
  });
  // Update note interaction when information used by this screen changes.
  useEffect(() => {
    document.title =
      mode === "admin"
        ? "Olive Branch Workspace"
        : mode === "download"
          ? "Olive Branch"
        : covers.find((candidate) => candidate.id === cover)?.name ?? "Game";
  }, [cover, mode]);
  // Web session provider: returns the perform.
  async function perform(work: () => Promise<void>) {
    if (working.current) return;
    working.current = true;
    setBusy(true);
    setMessage("");
    const token = epoch.current;
    // A token error is handled here instead of closing the app.
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
  // Authenticates the account for web session provider.
  async function authenticate(
    username: string,
    password: string,
    role: Role,
    create: boolean,
  ) {
    if ((role === "STAFF" || role === "ADMIN") !== (mode === "admin"))
      throw new Error(
        mode === "admin"
          ? "Use a Staff or Administrator account for this organization."
          : "Organization accounts sign in at admin.yourolivebranch.ca.",
      );
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
    // role is staff or role is admin is handled here by p.
    if (role === "STAFF" || role === "ADMIN") {
      setProfile(p);
      setHistory(loaded.history);
      setPhase("portal");
      return;
    }
    if (role === "SAFE_CONTACT") {
      setProfile(p);
      setHistory(loaded.history);
      setPhase("safe");
      setPage("Home");
      return;
    }
    const current: Enrollment | undefined = await (
      await database
    ).get("device", "current");
    // current checks whether path is /account/link before continuing.
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
    // Only continue with current when current and current uid is accounts identity uid.
    if (current && current.uid === accounts.identity?.uid) {
      setEnrollment({
        ...current,
        cover: p.coverType,
        triggerHash: p.triggerHash,
      });
      accounts.lock();
      const saved = await savedEntryLink(current.uid);
      const token = saved ? new URL(saved).hash.slice(1) : "";
      if (validEntryToken(token)) {
        pendingEntry.current = token;
        entryOwner.current = current.uid;
        setPhase("pin");
      } else {
        setPath("/account/link");
        setPhase("public");
      }
    } else {
      setOnboardingProfile(p);
      setPhase("setup");
    }
  }
  // Starts entry for web session provider.
  async function beginEntry() {
    setMessage("");
    if (!enrollment) {
      setPath("/account/sign-in");
      window.history.replaceState({}, "", "/");
      return;
    }
    const saved = await savedEntryLink(enrollment.uid);
    const token = saved ? new URL(saved).hash.slice(1) : "";
    if (!validEntryToken(token)) {
      setPath("/account/sign-in");
      window.history.replaceState({}, "", "/");
      return;
    }
    pendingEntry.current = token;
    entryOwner.current = enrollment.uid;
    setPhase("pin");
    window.history.replaceState({}, "", "/");
  }
  // returns the complete setup for web session provider.
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
      setProfile(p);
      setHistory(emptyHistory());
      setPage("Home");
      setPhase("safe");
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
  // Finds trigger for web session provider.
  async function searchTrigger(value: string) {
    if (!enrollment || enrollment.cover !== cover || enrollment.role !== "USER")
      return false;
    if ((await triggerHash(value)) !== enrollment.triggerHash) return false;
    // search trigger checks whether entry owner current is enrollment uid is false before continuing.
    if (entryOwner.current !== enrollment.uid) {
      const saved = await savedEntryLink(enrollment.uid);
      const token = saved ? new URL(saved).hash.slice(1) : "";
      if (!validEntryToken(token)) return false;
      pendingEntry.current = token;
      entryOwner.current = enrollment.uid;
    }
    setMessage("");
    setPhase("pin");
    return true;
  }
  // Unlocks the private area for web session provider.
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
  // returns the record for web session provider.
  async function record(category: Category, value: Event | Entry) {
    const token = epoch.current;
    await accounts.saveRecord(category, value);
    if (token !== epoch.current) return;
    setHistory((old) => ({
      ...old,
      [category]: [value, ...old[category].filter((e) => e.id !== value.id)],
    }));
  }
  // Web session provider: Saves profile.
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
  // Web session provider: Saves entry.
  async function saveEntry(entry: Entry) {
    await record("private_entries", entry);
    setMessage("Entry saved.");
  }
  // Web session provider: Reloads the latest data.
  async function refresh() {
    const token = epoch.current;
    const loaded = await accounts.load();
    if (token === epoch.current) {
      setProfile(loaded.profile);
      setHistory(loaded.history);
      setMessage("Account is up to date.");
    }
  }
  // Signs out the current account for web session provider.
  async function signOut() {
    const returnPath = profile?.role === "SAFE_CONTACT" || mode === "admin"
      ? "/account/sign-in"
      : mode === "download"
        ? "/download"
        : "/";
    await accounts.signOut();
    epoch.current++;
    setProfile(null);
    setHistory(emptyHistory());
    setEnrollment(null);
    await (await database).delete("device", "current");
    setPhase("public");
    go(returnPath);
  }
  return (
    <SessionContext.Provider
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
        beginEntry,
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
    </SessionContext.Provider>
  );
}
