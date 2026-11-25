// @vitest-environment happy-dom
import { act, cleanup, render, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installDomMocks } from "../test-utils/domTestHarness";
import { emptyHistory, emptyProfile, type Enrollment, type Profile } from "../domain/DomainModels";
import { useSession } from "./SessionContext";
import { extendIdleTimeoutOptions } from "./useSessionSecurity";

const mocks = vi.hoisted(() => ({
  mockDeviceStore: new Map<string, unknown>(),
  mockAccounts: {
    identity: { uid: "uid-1", username: "user_one", role: "USER" as const } as {
      uid: string;
      username: string;
      role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
    } | null,
    authenticate: vi.fn(),
    load: vi.fn(),
    unlock: vi.fn(),
    lock: vi.fn(),
    saveProfile: vi.fn(),
    saveRecord: vi.fn(),
    enroll: vi.fn(),
    signOut: vi.fn(),
  },
  mockAuthStateReady: vi.fn(async () => {}),
  authCurrentUid: "uid-1" as string | null,
  mockObserveAccountChanges: vi.fn(),
  mockSavedEntryLink: vi.fn(),
  mockIssueEntryLink: vi.fn(),
  mockVerifyEntryLink: vi.fn(),
  mockClearPreviewCache: vi.fn(),
  mockGetRecoveryStatus: vi.fn(),
  mockEnrollRecovery: vi.fn(),
  mockRecoverKeyset: vi.fn(),
  keysetCurrent: null as CryptoKey | null,
}));

vi.mock("../services/LocalDataStore", () => ({
  database: Promise.resolve({
    get: vi.fn(async (_store: string, key: string) => mocks.mockDeviceStore.get(key)),
    put: vi.fn(async (_store: string, value: unknown, key: string) => {
      mocks.mockDeviceStore.set(key, value);
    }),
    delete: vi.fn(async (_store: string, key: string) => {
      mocks.mockDeviceStore.delete(key);
    }),
  }),
}));

vi.mock("../services/AccountService", () => ({
  accounts: mocks.mockAccounts,
  cloudMode: true,
  firebase: vi.fn(async () => ({
    auth: {
      authStateReady: mocks.mockAuthStateReady,
      get currentUser() {
        return mocks.authCurrentUid ? { uid: mocks.authCurrentUid } : null;
      },
    },
  })),
  observeAccountChanges: (...args: unknown[]) => mocks.mockObserveAccountChanges(...args),
  accountKeysetSession: {
    current: () => mocks.keysetCurrent,
  },
}));

vi.mock("../services/PrivateEntryLinks", () => ({
  issueEntryLink: (...args: unknown[]) => mocks.mockIssueEntryLink(...args),
  savedEntryLink: (...args: unknown[]) => mocks.mockSavedEntryLink(...args),
  verifyEntryLink: (...args: unknown[]) => mocks.mockVerifyEntryLink(...args),
  validEntryToken: (token: string) => /^[A-Za-z0-9_-]{8,128}$/.test(token),
}));

vi.mock("../services/DocumentPreviewCache", () => ({
  clearDocumentPreviewCache: () => mocks.mockClearPreviewCache(),
}));

vi.mock("../services/BrowserAccountKeysetRecovery", () => ({
  getBrowserPasswordRecoveryStatus: (...args: unknown[]) => mocks.mockGetRecoveryStatus(...args),
  enrollBrowserAccountRecovery: (...args: unknown[]) => mocks.mockEnrollRecovery(...args),
  recoverBrowserAccountKeyset: (...args: unknown[]) => mocks.mockRecoverKeyset(...args),
}));

import { SessionProvider } from "./SessionProvider";

const {
  mockDeviceStore,
  mockAccounts,
  mockObserveAccountChanges,
  mockSavedEntryLink,
  mockIssueEntryLink,
  mockVerifyEntryLink,
  mockClearPreviewCache,
  mockGetRecoveryStatus,
  mockEnrollRecovery,
  mockRecoverKeyset,
} = mocks;

const enrollmentFixture: Enrollment = {
  uid: "uid-1",
  username: "user_one",
  webCover: "GAME",
  role: "USER",
  triggerHash: "hash",
  pinKey: { salt: "s", box: { iv: "i", data: "d" } },
  pinLength: 6,
  failures: 0,
};

const completeUserProfile: Profile = {
  ...emptyProfile("user_one", "USER"),
  fullName: "Alex Example",
  dob: "2000-01-01",
  triggerHash: "pin-hash",
};

describe("SessionProvider, SessionContext, and useSessionSecurity", () => {
  beforeEach(() => {
    installDomMocks();
    mockDeviceStore.clear();
    mocks.authCurrentUid = "uid-1";
    mockAccounts.identity = { uid: "uid-1", username: "user_one", role: "USER" };
    mocks.keysetCurrent = null;
    vi.clearAllMocks();
    mockObserveAccountChanges.mockResolvedValue(() => {});
    mockSavedEntryLink.mockResolvedValue("https://yourolivebranch.ca/#validtoken12345");
    mockIssueEntryLink.mockResolvedValue("https://yourolivebranch.ca/#newtoken12345");
    mockVerifyEntryLink.mockResolvedValue(true);
    mockGetRecoveryStatus.mockResolvedValue({ hasKeyset: true, enrolled: false });
    mockEnrollRecovery.mockResolvedValue(undefined);
    mockRecoverKeyset.mockResolvedValue(undefined);
    mockAccounts.load.mockResolvedValue({ profile: completeUserProfile, history: emptyHistory() });
    mockAccounts.unlock.mockResolvedValue({ profile: completeUserProfile, history: emptyHistory() });
    mockAccounts.saveProfile.mockImplementation(async (p: Profile) => p);
    mockAccounts.saveRecord.mockResolvedValue(undefined);
    mockAccounts.enroll.mockResolvedValue(enrollmentFixture);
    mockAccounts.signOut.mockResolvedValue(undefined);
    window.history.replaceState({}, "", "/");
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("throws when useSession is called outside SessionProvider and extends idle timeout options", () => {
    expect(() => renderHook(() => useSession())).toThrow("Missing session");
    expect(extendIdleTimeoutOptions(60)).toEqual([120, 300, 450]);
    expect(extendIdleTimeoutOptions(300)).toEqual([360, 450]);
    expect(extendIdleTimeoutOptions(450)).toEqual([]);
    expect(extendIdleTimeoutOptions(999)).toEqual([]);
  });

  it("handles full user sign-in, entry link, PIN unlock, password recovery, settings, notes, idle lock, and sign-out", async () => {
    mockDeviceStore.set("current", { ...enrollmentFixture, cover: "legacy" });
    mockAccounts.authenticate.mockResolvedValue({ profile: completeUserProfile, history: emptyHistory() });

    let latest!: ReturnType<typeof useSession>;
    function Probe() {
      latest = useSession();
      return <div data-testid="phase">{latest.phase}</div>;
    }

    render(
      <SessionProvider mode="user">
        <Probe />
      </SessionProvider>,
    );

    await waitFor(() => expect(latest.enrollment?.uid).toBe("uid-1"));
    expect(mockClearPreviewCache).toHaveBeenCalled();

    // Begin entry loads saved link and moves to PIN gate.
    await act(async () => {
      await latest.perform(() => latest.beginEntry());
    });
    expect(latest.phase).toBe("pin");

    // Unlock enters safe workspace and triggers recovery status + change observer.
    await act(async () => {
      await latest.perform(() => latest.unlock("123456"));
    });
    expect(latest.phase).toBe("safe");
    expect(latest.passwordRecoveryEnrolled).toBe(false);
    expect(mockObserveAccountChanges).toHaveBeenCalled();

    // Password recovery enable and unlock update state.
    mockGetRecoveryStatus.mockResolvedValueOnce({ hasKeyset: true, enrolled: true });
    await act(async () => {
      await latest.enablePasswordRecovery("SecretPass1!");
      await latest.recoverEncryptedAccess("SecretPass1!");
      await latest.refreshPasswordRecoveryStatus();
    });
    expect(latest.passwordRecoveryEnrolled).toBe(true);

    // Profile, entry, refresh, and idle timeout changes.
    await act(async () => {
      await latest.saveProfile(completeUserProfile, "Updated profile");
      await latest.saveEntry({
        id: "note-1",
        timestamp: 100,
        updatedAt: 100,
        kind: "Note",
        title: "Title",
        body: "Body",
        pinned: false,
        edits: [],
      });
      await latest.refresh();
      await latest.setIdleTimeoutSeconds(120);
    });
    expect(latest.idleTimeoutSeconds).toBe(120);

    // Simulate Escape quickExit while in safe phase.
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });
    expect(latest.phase).toBe("public");

    // Authenticate into setup when profile is incomplete, then completeSetup -> PIN.
    mockAccounts.authenticate.mockResolvedValueOnce({
      profile: emptyProfile("user_one", "USER"),
      history: emptyHistory(),
    });
    await act(async () => {
      await latest.perform(() => latest.authenticate("user_one", "Pass123!", "USER", false));
    });
    expect(latest.phase).toBe("setup");

    await act(async () => {
      await latest.perform(() => latest.completeSetup(completeUserProfile, "123456"));
    });
    expect(["link", "pin"]).toContain(latest.phase);

    // Sign out clears enrollment and returns to public phase.
    await act(async () => {
      await latest.signOut();
    });
    expect(latest.phase).toBe("public");
    expect(mockAccounts.signOut).toHaveBeenCalled();
  });

  it("handles navigation, hashchange, popstate, visibilitychange, idle timeout, and organization portal mode", async () => {
    vi.useFakeTimers();
    const staffProfile = { ...emptyProfile("staff_one", "STAFF"), fullName: "Staff One", dob: "1990-01-01" };
    mockAccounts.authenticate.mockResolvedValue({ profile: staffProfile, history: emptyHistory() });

    let latest!: ReturnType<typeof useSession>;
    function Probe() {
      latest = useSession();
      return null;
    }

    const { rerender } = render(
      <SessionProvider mode="admin">
        <Probe />
      </SessionProvider>,
    );

    // Wrong role on admin site rejects cleanly through perform().
    await act(async () => {
      await latest.perform(() => latest.authenticate("user_one", "Pass123!", "USER", false));
    });
    expect(latest.message).toContain("Staff or Administrator");

    // Staff authentication enters portal phase.
    await act(async () => {
      await latest.perform(() => latest.authenticate("staff_one", "Pass123!", "STAFF", false));
    });
    expect(latest.phase).toBe("portal");

    // Back popstate in organization mode pushes state rather than locking.
    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(latest.phase).toBe("portal");

    // Safe contact + user security guards on user mode.
    rerender(
      <SessionProvider mode="user">
        <Probe />
      </SessionProvider>,
    );
    mockAccounts.authenticate.mockResolvedValueOnce({
      profile: { ...emptyProfile("safe_one", "SAFE_CONTACT"), fullName: "Safe", dob: "1992-01-01" },
      history: emptyHistory(),
    });
    await act(async () => {
      await latest.perform(() => latest.authenticate("safe_one", "Pass123!", "SAFE_CONTACT", false));
    });
    expect(latest.phase).toBe("safe");

    // Test user-role idle countdown and hash/popstate/visibility listeners.
    mockAccounts.authenticate.mockResolvedValueOnce({
      profile: completeUserProfile,
      history: emptyHistory(),
    });
    mockDeviceStore.set("current", enrollmentFixture);
    await act(async () => {
      latest.go("/account/link");
    });
    await act(async () => {
      await latest.perform(() => latest.authenticate("user_one", "Pass123!", "USER", false));
    });
    expect(latest.phase).toBe("link");

    await act(async () => {
      window.history.replaceState({}, "", "/#validhash12345");
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(latest.phase).toBe("pin");
    await act(async () => {
      await latest.perform(() => latest.unlock("123456"));
    });
    expect(latest.phase).toBe("safe");

    // Advance clock to trigger idle warning and automatic quickExit.
    act(() => {
      vi.advanceTimersByTime(46_000);
    });
    expect(latest.idleSeconds).not.toBeNull();
    act(() => {
      document.dispatchEvent(new Event("pointerdown"));
    });
    expect(latest.idleSeconds).toBeNull();
    act(() => {
      vi.advanceTimersByTime(61_000);
    });
    expect(latest.phase).toBe("public");

    // Pagehide, popstate, beginEntry failure, invalid pendingEntry link, and account-change observer.
    act(() => {
      window.dispatchEvent(new Event("pagehide"));
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(latest.phase).toBe("public");

    mockSavedEntryLink.mockResolvedValueOnce(null);
    await act(async () => {
      await latest.perform(() => latest.beginEntry());
    });
    expect(latest.path).toBe("/account/sign-in");

    mockVerifyEntryLink.mockResolvedValueOnce(false);
    await act(async () => {
      window.history.replaceState({}, "", "/#validhash99999");
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    await act(async () => {
      await latest.perform(() => latest.authenticate("user_one", "Pass123!", "USER", false));
    });
    expect(latest.message).toContain("invalid");
  });

  it("covers completeSetup for staff and safe contacts, unlock link guards, and account-change observers", async () => {
    let observedHandler: (() => void) | undefined;
    let observedErr: ((err: unknown) => void) | undefined;
    mockObserveAccountChanges.mockImplementation(((_uid: string, onChanged: () => void, onErr: (err: unknown) => void) => {
      observedHandler = onChanged;
      observedErr = onErr;
      return Promise.resolve(() => {});
    }) as never);
    mockDeviceStore.set("current", enrollmentFixture);

    let latest!: ReturnType<typeof useSession>;
    function Probe() {
      latest = useSession();
      return null;
    }

    render(
      <SessionProvider mode="user">
        <Probe />
      </SessionProvider>,
    );

    await waitFor(() => expect(latest.enrollment?.uid).toBe("uid-1"));

    // Unlock without valid pendingEntry sets message.
    await act(async () => {
      await latest.perform(() => latest.unlock("123456"));
    });
    expect(latest.message).toContain("personal link");

    // Enter safe phase and trigger account-change observer callback (150ms debounce) + error callback.
    await act(async () => {
      await latest.perform(() => latest.beginEntry());
      await latest.perform(() => latest.unlock("123456"));
    });
    expect(latest.phase).toBe("safe");
    await act(async () => {
      observedHandler?.();
      await new Promise((r) => setTimeout(r, 200));
    });
    expect(mockAccounts.load).toHaveBeenCalled();
    act(() => {
      observedErr?.({ code: "permission-denied" });
    });
    expect(latest.message).toContain("signed in");

    // completeSetup transitions directly for STAFF and SAFE_CONTACT.
    await act(async () => {
      await latest.perform(() =>
        latest.completeSetup({ ...emptyProfile("staff_new", "STAFF"), fullName: "S", dob: "1990-01-01" }, "123456"),
      );
    });
    expect(latest.phase).toBe("portal");

    await act(async () => {
      await latest.perform(() =>
        latest.completeSetup({ ...emptyProfile("safe_new", "SAFE_CONTACT"), fullName: "C", dob: "1991-01-01" }, "123456"),
      );
    });
    expect(latest.phase).toBe("safe");

    // beginEntry when device enrollment record was removed resets to sign-in.
    mockDeviceStore.clear();
    await act(async () => {
      await latest.perform(() => latest.beginEntry());
    });
    expect(latest.path).toBe("/account/sign-in");
  });
});
