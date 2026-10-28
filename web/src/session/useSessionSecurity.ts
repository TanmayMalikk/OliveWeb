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
import { coverPath, emptyHistory, type Enrollment, type Profile } from "../domain/DomainModels";
import type { SiteMode } from "../sites/SiteRouting";
import type { SessionPhase } from "./SessionContext";

type Setter<T> = Dispatch<SetStateAction<T>>;

export function useSessionSecurity({
  enrollment,
  entryOwner,
  epoch,
  mode,
  pendingEntry,
  phase,
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
  const lastInteraction = useRef(Date.now());
  phaseRef.current = phase;

  useEffect(() => {
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
    const onPop = () => {
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
  }, [enrollment, entryOwner, epoch, mode, pendingEntry]);

  useEffect(() => {
    const onHidden = () => {
      if (document.hidden && phaseRef.current !== "public") quickExit();
    };
    const onKey = (event: KeyboardEvent) => {
      if (
        event.key === "Escape" &&
        ["safe", "pin", "portal"].includes(phaseRef.current)
      ) {
        event.preventDefault();
        quickExit();
      }
    };
    const onPageHide = () => {
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
  }, [epoch, quickExit]);

  const noteInteraction = useCallback(() => {
    lastInteraction.current = Date.now();
    setIdleSeconds(null);
  }, [setIdleSeconds]);

  useEffect(() => {
    if (phase !== "safe" && phase !== "portal") return;
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
  }, [phase, noteInteraction, quickExit, setIdleSeconds]);

  return noteInteraction;
}
