// Signed-in users: loads the account and keeps web session state in sync.
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import {
  accounts, cloudMode, firebase, observeAccountChanges,
} from "../services/AccountService";
import { database } from "../services/LocalDataStore";
import {
  issueEntryLink, savedEntryLink, verifyEntryLink, validEntryToken,
} from "../services/PrivateEntryLinks";
import {
  canonicalPath, siteDestination, siteMode, webCover, type SiteMode,
} from "../sites/SiteRouting";
import {
  emptyHistory, friendlyError, newEvent,
  type Enrollment, type History, type Profile, type Role,
  type Page, type Entry, type Event, type Category,
} from "../domain/DomainModels";
import { SessionContext, useSession, type SessionValue } from "./SessionContext";
import { useSessionSecurity } from "./useSessionSecurity";
export { useSession };

// Converts Firestore permission errors into a clear user-facing explanation.
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

// Keeps sign-in, private-entry, and account data together for every web screen.
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
        ? "/get"
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
  const [requestedConversation, setRequestedConversation] = useState<{ id: string; uid: string } | null>(null);
  const [onboardingProfile, setOnboardingProfile] = useState<Profile | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [idleSeconds, setIdleSeconds] = useState<number | null>(null);
  const epoch = useRef(0);
  const working = useRef(false);

  // Reloads account files when another phone or browser changes this account.
  useEffect(() => {
    const identity = accounts.identity;
    if (!cloudMode || !identity || (phase !== "safe" && phase !== "portal")) return;
    let active = true;
    let timer = 0;
    let stop: (() => void) | undefined;
    void observeAccountChanges(
      identity.uid,
      () => {
        window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          void accounts
            .load()
            .then((loaded) => {
              if (!active || accounts.identity?.uid !== identity.uid) return;
              setProfile(loaded.profile);
              setHistory(loaded.history);
            })
            .catch((error) => {
              if (active) setMessage(accountSyncError(error));
            });
        }, 150);
      },
      (error) => {
        if (active) setMessage(accountSyncError(error));
      },
    )
      .then((cleanup) => {
        if (active) stop = cleanup;
        else cleanup();
      })
      .catch((error) => {
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
      const canonical = canonicalPath(to);
      const destination = new URL(siteDestination(canonical, location.origin));
      const destinationMode = siteMode(canonical);
      const internal =
        mode === "admin"
          ? canonical !== "/" && destinationMode !== "download"
          : mode === "download"
            ? destinationMode === "download"
            : destinationMode === "user";
      // Moving between /get, /admin, and the public app reloads the matching app shell.
      if (!internal) {
        accounts.lock();
        window.location.assign(destination.href);
        return;
      }
      const shellPath = mode === "admin" ? "/admin" : mode === "download" ? "/get" : "/";
      window.history.replaceState({}, "", shellPath);
      setPath(canonical);
      window.scrollTo({ top: 0 });
    },
    [mode],
  );

  useEffect(() => {
    const shellPath = mode === "admin" ? "/admin" : mode === "download" ? "/get" : "/";
    if (location.pathname !== shellPath || location.search || location.hash)
      window.history.replaceState({}, "", shellPath);
  }, [mode]);

  // Restores this browser's saved enrollment and continues a pending private entry.
  useEffect(() => {
    let live = true;
    void database
      .then((db) => db.get("device", "current"))
      .then(async (value) => {
        if (!live) return;
        const stored = value as (Enrollment & { cover?: unknown }) | undefined;
        const savedEnrollment = stored ? { ...stored, webCover: webCover.id } : null;
        if (savedEnrollment) delete savedEnrollment.cover;
        setEnrollment(savedEnrollment);
        if (savedEnrollment && (stored?.webCover !== webCover.id || stored?.cover !== undefined))
          await (await database).put("device", savedEnrollment, "current");
        // A saved account and a pending link are both needed before showing PIN entry.
        if (savedEnrollment && pendingEntry.current) {
          let authenticated = true;
          if (cloudMode) {
            const { auth } = await firebase();
            await auth.authStateReady();
            authenticated = auth.currentUser?.uid === savedEnrollment.uid;
          }
          const entryToken = pendingEntry.current;
          if (entryToken && authenticated && live && pendingEntry.current === entryToken) {
            entryOwner.current = savedEnrollment.uid;
            setPhase("pin");
          }
        }
      })
      .catch(() =>
        setMessage("Browser storage is unavailable. Enable site storage and try again."),
      );
    return () => {
      live = false;
    };
  }, [entryAttempt]);

  const quickExit = useCallback(() => {
    const returnPath =
      profile?.role === "SAFE_CONTACT"
        ? "/account/sign-in"
        : mode === "admin"
          ? "/account/sign-in"
          : mode === "download"
            ? "/get"
            : "/";
    epoch.current++;
    entryOwner.current = null;
    pendingEntry.current = null;
    accounts.lock();
    setProfile(null);
    setOnboardingProfile(null);
    setPersonalLink("");
    setHistory(emptyHistory());
    setPage("Home");
    setRequestedConversation(null);
    setMessage("");
    setIdleSeconds(null);
    setBusy(false);
    setPhase("public");
    window.history.replaceState(
      {},
      "",
      mode === "admin" ? "/admin" : mode === "download" ? "/get" : "/",
    );
    setPath(returnPath);
    window.scrollTo({ top: 0 });
  }, [mode, profile?.role]);

  const noteInteraction = useSessionSecurity({
    enrollment, entryOwner, epoch, mode, pendingEntry, phase,
    role: profile?.role ?? null, quickExit, setBusy,
    setEnrollmentAttempt: setEntryAttempt, setHistory, setIdleSeconds,
    setMessage, setOnboardingProfile, setPath, setPersonalLink, setPhase, setProfile,
  });

  // Keeps the browser-tab title matched to the current cover or workspace.
  useEffect(() => {
    document.title =
      mode === "admin"
        ? "Olive Branch Workspace"
        : mode === "download"
          ? "Olive Branch"
          : webCover.name;
  }, [mode]);

  // Runs one account task at a time and keeps loading and error messages consistent.
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
      if (token === epoch.current) setBusy(false);
    }
  }

  // Signs in or creates an account, then opens the screen allowed for its role.
  async function authenticate(username: string, password: string, role: Role, create: boolean) {
    if ((role === "STAFF" || role === "ADMIN") !== (mode === "admin"))
      throw new Error(
        mode === "admin"
          ? "Use a Staff or Administrator account for this organization."
          : "Organization accounts sign in at yourolivebranch.ca/admin.",
      );
    const token = epoch.current;
    const loaded = await accounts.authenticate(username, password, role, create);
    if (token !== epoch.current) {
      accounts.lock();
      return;
    }
    const p = loaded.profile;
    if (pendingEntry.current) {
      if (!(await verifyEntryLink(accounts.identity!.uid, pendingEntry.current))) {
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
    if (role === "SAFE_CONTACT") {
      setProfile(p);
      setHistory(loaded.history);
      setPhase("safe");
      setPage("Home");
      return;
    }
    const current: Enrollment | undefined = await (await database).get("device", "current");
    if (path === "/account/link") {
      setPersonalLink(await issueEntryLink(accounts.identity!.uid));
      setEnrollment(
        current && current.uid === accounts.identity?.uid
          ? { ...current, webCover: webCover.id }
          : null,
      );
      setPhase("link");
      return;
    }
    if (current && current.uid === accounts.identity?.uid) {
      setEnrollment({ ...current, webCover: webCover.id, triggerHash: p.triggerHash });
      accounts.lock();
      const saved = await savedEntryLink(current.uid);
      const savedToken = saved ? new URL(saved).hash.slice(1) : "";
      if (validEntryToken(savedToken)) {
        pendingEntry.current = savedToken;
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

  // Opens PIN entry only when this browser has a valid saved private link.
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

  // Saves the new profile and PIN, then creates this browser's private entry link.
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

  // Verifies the link and PIN before loading private account data.
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
    setProfile(loaded.profile);
    setHistory({ ...loaded.history, triggers: [event, ...loaded.history.triggers] });
    setPhase("safe");
    setPage("Home");
    noteInteraction();
  }

  // Saves a note or history entry, then refreshes the screen with the new record.
  async function record(category: Category, value: Event | Entry) {
    const token = epoch.current;
    await accounts.saveRecord(category, value);
    if (token !== epoch.current) return;
    setHistory((old) => ({
      ...old,
      [category]: [value, ...old[category].filter((e) => e.id !== value.id)],
    }));
  }

  // Saves account settings and tells the other signed-in devices to refresh.
  async function saveProfile(p: Profile, action = "Saved profile settings") {
    const token = epoch.current;
    const saved = await accounts.saveProfile(p);
    if (token !== epoch.current) return;
    setProfile(saved);
    if (enrollment)
      setEnrollment({ ...enrollment, webCover: webCover.id, triggerHash: p.triggerHash });
    await record("settings", newEvent(action));
    setMessage("Changes saved.");
  }

  // Saves a private note, then reloads the list so it appears immediately.
  async function saveEntry(entry: Entry) {
    await record("private_entries", entry);
    setMessage("Entry saved.");
  }

  // Reloads profile, history, and notes changed by another signed-in device.
  async function refresh() {
    const token = epoch.current;
    const loaded = await accounts.load();
    if (token === epoch.current) {
      setProfile(loaded.profile);
      setHistory(loaded.history);
      setMessage("Account is up to date.");
    }
  }

  // Ends the web session and returns to the public cover.
  async function signOut() {
    const returnPath =
      profile?.role === "SAFE_CONTACT" || mode === "admin"
        ? "/account/sign-in"
        : mode === "download"
          ? "/get"
          : "/";
    await accounts.signOut();
    epoch.current++;
    setRequestedConversation(null);
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
        path, go, phase, personalLink, hasEntryToken: Boolean(pendingEntry.current),
        profile, history, enrollment, page, setPage, requestedConversation, setRequestedConversation, busy, message,
        clearMessage: () => setMessage(""), perform, authenticate, completeSetup,
        beginEntry, quickExit, unlock, saveProfile, saveEntry,
        record, refresh, signOut, idleSeconds, noteInteraction, cloudMode, onboardingProfile,
      }}
    >
      {children}
    </SessionContext.Provider>
  );
}
