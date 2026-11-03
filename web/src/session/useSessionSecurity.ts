// Signed-in users: locks or exits the web safe app after privacy events.
import {
  useCallback,
  useEffect,
  useRef,
  type Dispatch,
  type MutableRefObject,
  type SetStateAction,
} from "react";
import { accounts } from "../services/AccountService";
import { validEntryToken } from "../services/PrivateEntryLinks";
import { coverPath, emptyHistory, type Enrollment, type Profile, type Role } from "../domain/DomainModels";
import type { SiteMode } from "../sites/SiteRouting";
import type { SessionPhase } from "./SessionContext";

type Setter<T> = Dispatch<SetStateAction<T>>;

// Web private entry: Keeps the safe session locked when it is not in use. It watches navigation, entry links, and idle time, then clears private data before returning to a cover.
export function useSessionSecurity({
  enrollment,
  entryOwner,
  epoch,
  mode,
  pendingEntry,
  phase,
  role,
  quickExit,
  setBusy,
  setEnrollmentAttempt,
  setHistory,
  setIdleSeconds,
  setMessage,
  setOnboardingProfile,
  setPath,
  setPersonalLink,
  setPhase,
  setProfile,
}: {
  enrollment: Enrollment | null;
  entryOwner: MutableRefObject<string | null>;
  epoch: MutableRefObject<number>;
  mode: SiteMode;
  pendingEntry: MutableRefObject<string | null>;
  phase: SessionPhase;
  role: Role | null;
  quickExit: () => void;
  setBusy: Setter<boolean>;
  setEnrollmentAttempt: Setter<number>;
  setHistory: Setter<ReturnType<typeof emptyHistory>>;
  setIdleSeconds: Setter<number | null>;
  setMessage: Setter<string>;
  setOnboardingProfile: Setter<Profile | null>;
  setPath: Setter<string>;
  setPersonalLink: Setter<string>;
  setPhase: Setter<SessionPhase>;
  setProfile: Setter<Profile | null>;
}) {
  const phaseRef = useRef(phase);
  const roleRef = useRef(role);
  const lastInteraction = useRef(Date.now());
  phaseRef.current = phase;
  roleRef.current = role;
  const buttonOnlyExit = role === "SAFE_CONTACT" || role === "STAFF" || role === "ADMIN";

  // Web private entry: Installs navigation guards for the private session. The handlers clear account data before browser back or hash changes can expose another screen.
  useEffect(() => {
    // Web private entry: Locks the safe session. It clears account data, history, setup state, messages, and the current private route.
    const resetPrivateSession = () => {
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
      setMessage("");
    };
    // The browser Back button locks private pages before returning to the cover.
    const onPop = () => {
      if (buttonOnlyExit) {
        window.history.pushState({}, "", window.location.pathname);
        return;
      }
      resetPrivateSession();
      setPath(
        mode === "admin"
          ? "/account/sign-in"
          : mode === "download"
            ? "/download"
            : coverPath(enrollment?.cover ?? "GAME"),
      );
      window.history.replaceState({}, "", "/");
    };
    // A changed URL can start private entry only when its saved token is valid.
    const onHash = () => {
      const candidate = location.hash.slice(1);
      if (!validEntryToken(candidate)) return;
      resetPrivateSession();
      pendingEntry.current = candidate;
      window.history.replaceState({}, "", "/");
      setPath("/account/sign-in");
      if (enrollment) {
        entryOwner.current = enrollment.uid;
        setPhase("pin");
      } else setEnrollmentAttempt((attempt) => attempt + 1);
    };
    addEventListener("popstate", onPop);
    addEventListener("hashchange", onHash);
    return () => {
      removeEventListener("popstate", onPop);
      removeEventListener("hashchange", onHash);
    };
  }, [buttonOnlyExit, enrollment, entryOwner, epoch, mode, pendingEntry]);

  // Watches browser privacy events while a private web session is open.
  useEffect(() => {
    // User accounts quick-exit when their private tab becomes hidden.
    const onHidden = () => {
      if (document.hidden && phaseRef.current === "safe" && roleRef.current === "USER") quickExit();
    };
    // Escape provides a fast exit unless this role requires the exit button.
    const onKey = (event: KeyboardEvent) => {
      if (
        event.key === "Escape" &&
        !buttonOnlyExit &&
        ["safe", "pin", "portal"].includes(phaseRef.current)
      ) {
        event.preventDefault();
        quickExit();
      }
    };
    // Locks private data when the page closes or moves into browser history.
    const onPageHide = () => {
      if (buttonOnlyExit) return;
      if (phaseRef.current !== "public") quickExit();
      else {
        epoch.current++;
        accounts.lock();
      }
    };
    document.addEventListener("visibilitychange", onHidden);
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("visibilitychange", onHidden);
      window.removeEventListener("pagehide", onPageHide);
      window.removeEventListener("keydown", onKey);
    };
  }, [buttonOnlyExit, epoch, quickExit]);

  const noteInteraction = useCallback(() => {
    lastInteraction.current = Date.now();
    setIdleSeconds(null);
  }, [setIdleSeconds]);

  // Starts the user idle timer and resets it after keyboard, pointer, or form activity.
  useEffect(() => {
    // Organization workspaces stay open until their user signs out or exits manually.
    if (phase !== "safe" || buttonOnlyExit) {
      setIdleSeconds(null);
      return;
    }
    noteInteraction();
    const events = ["pointerdown", "keydown", "input"] as const;
    events.forEach((event) => document.addEventListener(event, noteInteraction));
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
      events.forEach((event) =>
        document.removeEventListener(event, noteInteraction),
      );
    };
  }, [buttonOnlyExit, phase, noteInteraction, quickExit, setIdleSeconds]);

  return noteInteraction;
}
