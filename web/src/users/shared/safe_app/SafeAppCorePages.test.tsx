// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyHistory, emptyProfile, type Event, type Page } from "../../../domain/DomainModels";
import type { BrowserDocument } from "../../../services/MediaService";
import { installDomMocks, renderWithSession } from "../../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "uid-1", username: "user_one", role: "USER" as const } as {
    uid: string;
    username: string;
    role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
  },
  cloudMode: true,
  changePassword: vi.fn(async (_cur: string, _next: string) => {}),
  changePin: vi.fn(async (_cur: string, _next: string, _p: unknown) => {}),
  triggerHash: vi.fn(async (phrase: string) => `hash:${phrase}`),
  observePlan: vi.fn(async (..._args: unknown[]) => () => {}),
  register: vi.fn(async (..._args: unknown[]) => {}),
  savedEntryLink: vi.fn(async (..._args: unknown[]) => "https://yourolivebranch.ca/#savedtoken12345"),
  issueEntryLink: vi.fn(async (..._args: unknown[]) => "https://yourolivebranch.ca/#rotatedtoken12345"),
  getRecoveryStatus: vi.fn(async () => ({ hasKeyset: true, enrolled: false })),
}));

vi.mock("../../../services/AccountService", () => ({
  get cloudMode() {
    return mocks.cloudMode;
  },
  accounts: {
    get identity() {
      return mocks.identity;
    },
    changePassword: (...args: [string, string]) => mocks.changePassword(...args),
    changePin: (...args: [string, string, unknown]) => mocks.changePin(...args),
  },
  triggerHash: (...args: [string]) => mocks.triggerHash(...args),
}));

vi.mock("../../../services/SafetyPlanService", () => ({
  observePlan: (...args: unknown[]) => mocks.observePlan(...args),
}));

vi.mock("../../../services/MessagingService", () => ({
  register: (...args: unknown[]) => mocks.register(...args),
}));

vi.mock("../../../services/PrivateEntryLinks", () => ({
  savedEntryLink: (...args: unknown[]) => mocks.savedEntryLink(...args),
  issueEntryLink: (...args: unknown[]) => mocks.issueEntryLink(...args),
}));

vi.mock("../../../services/LocalDataStore", () => ({
  database: Promise.resolve({ get: vi.fn(), put: vi.fn(), delete: vi.fn() }),
}));

vi.mock("../../../session/SessionProvider", async () => {
  const { useSession } = await import("../../../session/SessionContext");
  return { useSession };
});

vi.mock("./FriendsAndMessagesPage", () => ({ default: () => <div>FriendsMock</div> }));
vi.mock("./SafetyPlanPage", () => ({ default: () => <div>SafetyPlanMock</div> }));
vi.mock("./SupportAndLocationPages", () => ({
  DocumentsPage: () => <div>DocsMock</div>,
  LocationPage: () => <div>LocationMock</div>,
  StaffHelpPage: () => <div>StaffHelpMock</div>,
}));
vi.mock("../../user/contacts_and_notes/UserNotesPage", () => ({ default: () => <div>NotesMock</div> }));
vi.mock("../../user/contacts_and_notes/UserContactsPage", () => ({ default: () => <div>ContactsMock</div> }));

import SafeApp from "./SafeAppLayout";
import Home from "./SafeAppDashboard";
import Profile from "./AccountProfilePage";
import Settings from "./AccountSettingsPage";
import Activity, { formatDateTime } from "./ActivityHistoryPage";
import HotlinesPage from "./HotlinesPage";
import { SafeMediaViewer } from "./SafeMediaViewer";
import WalkthroughPage from "./WalkthroughPage";
import { WalkthroughActionContext, WalkthroughCoachmark } from "./WalkthroughCoachmark";

describe("SafeApp core pages, layout, settings, hotlines, media viewer, and walkthrough", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.identity = { uid: "uid-1", username: "user_one", role: "USER" };
    mocks.cloudMode = true;
    window.localStorage.clear();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn(async () => {}) },
    });
    mocks.observePlan.mockImplementation(async (_uid, cb) => {
      (cb as (p: { consultationRequested: boolean; plansCompletedAt: number }) => void)({
        consultationRequested: false,
        plansCompletedAt: 100,
      });
      return () => {};
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders SafeAppLayout, auto-launches walkthrough, navigates pages, and handles idle warning", async () => {
    const setPage = vi.fn();
    const quickExit = vi.fn();
    const noteInteraction = vi.fn();
    const setIdleTimeoutSeconds = vi.fn(async () => {});
    const clearMessage = vi.fn();
    const signOut = vi.fn(async () => {});

    const userProfile = { ...emptyProfile("user_one", "USER"), fullName: "Alex", dob: "1995-04-12" };
    renderWithSession(<SafeApp />, {
      profile: userProfile,
      page: "Home",
      message: "Saved",
      setPage,
      quickExit,
      noteInteraction,
      setIdleTimeoutSeconds,
      clearMessage,
      signOut,
    });

    await waitFor(() => expect(screen.getByTestId("walkthrough-active-feature")).toBeTruthy());
    expect(screen.getByText(/Your safety plan and backup plan are complete/i)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Dismiss" })[0]);
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(clearMessage).toHaveBeenCalled();

    // Advance, go back, open contents, and start tour from WalkthroughPage.
    fireEvent.click(screen.getByTestId("walkthrough-tour-next"));
    fireEvent.click(screen.getByTestId("walkthrough-tour-prev"));
    fireEvent.click(screen.getByTestId("walkthrough-tour-toc"));
    expect(setPage).toHaveBeenCalledWith("Walkthrough");

    // Toggle menu, open Settings icon, sign out, and Quick Exit.
    fireEvent.click(screen.getByRole("button", { name: "Navigation" }));
    fireEvent.click(screen.getByRole("button", { name: "Open settings" }));
    expect(setPage).toHaveBeenCalledWith("Settings");
    fireEvent.click(screen.getByRole("button", { name: /Quick Exit/i }));
    expect(quickExit).toHaveBeenCalled();

    // Re-render with idle warning modal and extend buttons.
    const idleSessionProps = {
      profile: userProfile,
      page: "Walkthrough" as Page,
      idleSeconds: 10,
      idleTimeoutSeconds: 60,
      setPage,
      quickExit,
      noteInteraction,
      setIdleTimeoutSeconds,
      clearMessage,
      signOut,
    };
    cleanup();
    renderWithSession(<SafeApp />, idleSessionProps);
    await waitFor(() => expect(screen.getByText(/Your Safe Space is about to lock/i)).toBeTruthy());
    fireEvent.click(screen.getByTestId("idle-extend-120"));
    await waitFor(() => expect(setIdleTimeoutSeconds).toHaveBeenCalledWith(120));
    fireEvent.click(screen.getByRole("button", { name: "Stay here" }));
    expect(noteInteraction).toHaveBeenCalled();

    // SAFE_CONTACT navigation mode.
    cleanup();
    const contactProfile = { ...emptyProfile("safe_one", "SAFE_CONTACT"), fullName: "Sam", dob: "1990-01-01" };
    renderWithSession(<SafeApp />, { profile: contactProfile, page: "Home", setPage });
    expect(screen.getByText("Safe contact space")).toBeTruthy();
  });

  it("renders SafeAppDashboard for USER and SAFE_CONTACT and routes shortcut clicks", () => {
    const setPage = vi.fn();
    renderWithSession(<Home />, { profile: emptyProfile("u1", "USER"), setPage });
    fireEvent.click(screen.getByRole("button", { name: /Open safety plan/i }));
    fireEvent.click(screen.getByRole("button", { name: /Hotlines/i }));
    expect(setPage).toHaveBeenCalledWith("SafetyPlan");
    expect(setPage).toHaveBeenCalledWith("Hotlines");

    cleanup();
    renderWithSession(<Home />, { profile: emptyProfile("s1", "SAFE_CONTACT"), setPage });
    fireEvent.click(screen.getByRole("button", { name: /View shared plans/i }));
    expect(setPage).toHaveBeenCalledWith("SafetyPlan");
  });

  it("drives AccountProfilePage, AccountSettingsPage, and ActivityHistoryPage", async () => {
    const saveProfile = vi.fn(async () => {});
    const record = vi.fn(async () => {});
    const setIdleTimeoutSeconds = vi.fn(async () => {});
    const refresh = vi.fn(async () => {});

    const fullProfile = {
      ...emptyProfile("user_one", "USER"),
      fullName: "Alex Rivera",
      dob: "1995-04-12",
      email: "alex@example.com",
      emailCommunicationConsent: true,
      address: { line1: "1 Main", line2: "", city: "Edmonton", province: "AB", postalCode: "T5J 1N3", country: "Canada" },
    };

    renderWithSession(<Profile />, { profile: fullProfile, saveProfile });
    const profileForm = () => screen.getByRole("button", { name: /Save profile/i }).closest("form")!;

    fireEvent.change(screen.getByLabelText("Email (optional)"), { target: { value: "invalid" } });
    fireEvent.submit(profileForm());

    fireEvent.change(screen.getByLabelText("Email (optional)"), { target: { value: "valid@example.com" } });
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.change(screen.getByLabelText("Street"), { target: { value: "2 Jasper Ave" } });
    fireEvent.submit(profileForm());
    await waitFor(() => expect(saveProfile).toHaveBeenCalled());

    // AccountSettingsPage: idle delay, copy/replace link, password, PIN, trigger, and sync.
    cleanup();
    const events: Event[] = [
      {
        id: "e1",
        timestamp: Date.now(),
        area: "Profile",
        action: "Updated profile",
        changes: [{ field: "City", before: "", after: "Edmonton" }],
      },
      { id: "e2", timestamp: Date.now() - 86400_000 * 5, source: "Web", lastResult: "Accepted" },
    ];
    renderWithSession(
      <WalkthroughActionContext.Provider value={{ stepId: "settings-link", action: "settings_open_replace_link" }}>
        <Settings />
      </WalkthroughActionContext.Provider>,
      {
        profile: fullProfile,
        history: { ...emptyHistory(), settings: events, triggers: events },
        idleTimeoutSeconds: 60,
        setIdleTimeoutSeconds,
        saveProfile,
        record,
        refresh,
        passwordRecoveryEnrolled: true,
        hasEncryptedAccess: true,
      },
    );

    await waitFor(() => expect(screen.getByText(/Replace your personal link\?/i)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Create replacement" }));
    await waitFor(() => expect(mocks.issueEntryLink).toHaveBeenCalledWith("uid-1"));
    expect(screen.getByText("Replacement personal link created.")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));
    await waitFor(() => expect(screen.getByText("Copied")).toBeTruthy());

    fireEvent.change(screen.getByLabelText("Idle lock delay"), { target: { value: "120" } });
    await waitFor(() => expect(setIdleTimeoutSeconds).toHaveBeenCalledWith(120));

    // Change password error & success.
    const pwdForm = screen.getByRole("button", { name: "Change password" }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Current password"), { target: { value: "OldPass1!" } });
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "weak" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "weak" } });
    fireEvent.submit(pwdForm);
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "NewPass1!" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "NewPass1!" } });
    fireEvent.submit(pwdForm);
    await waitFor(() => expect(mocks.changePassword).toHaveBeenCalledWith("OldPass1!", "NewPass1!"));

    // Change PIN error & success.
    const pinForm = screen.getByRole("button", { name: "Change PIN" }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Current PIN"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("New PIN"), { target: { value: "12" } });
    fireEvent.change(screen.getByLabelText("Confirm new PIN"), { target: { value: "12" } });
    fireEvent.submit(pinForm);
    fireEvent.change(screen.getByLabelText("New PIN"), { target: { value: "5678" } });
    fireEvent.change(screen.getByLabelText("Confirm new PIN"), { target: { value: "5678" } });
    fireEvent.submit(pinForm);
    await waitFor(() => expect(mocks.changePin).toHaveBeenCalled());

    // Change Android trigger.
    const trigForm = screen.getByRole("button", { name: "Save trigger" }).closest("form")!;
    fireEvent.change(screen.getByLabelText("New trigger phrase"), { target: { value: "ab" } });
    fireEvent.submit(trigForm);
    fireEvent.change(screen.getByLabelText("New trigger phrase"), { target: { value: "newphrase" } });
    fireEvent.submit(trigForm);
    await waitFor(() => expect(mocks.triggerHash).toHaveBeenCalledWith("newphrase"));

    fireEvent.click(screen.getByRole("button", { name: /Refresh account/i }));
    expect(refresh).toHaveBeenCalled();

    // ActivityHistoryPage.
    cleanup();
    expect(formatDateTime(Date.now())).toBeTruthy();
    renderWithSession(<Activity />, {
      history: { ...emptyHistory(), settings: events, triggers: events },
    });
    fireEvent.click(screen.getByRole("button", { name: "Triggers" }));
    fireEvent.change(screen.getByLabelText("From date"), { target: { value: "2099-01-01" } });
    fireEvent.change(screen.getByLabelText("To date"), { target: { value: "2099-01-02" } });
    expect(screen.getByText(/A little quiet here/i)).toBeTruthy();
  });

  it("drives HotlinesPage filters, SafeMediaViewer formats, WalkthroughPage, and WalkthroughCoachmark", async () => {
    const profile = {
      ...emptyProfile("user_one", "USER"),
      address: { ...emptyProfile("user_one", "USER").address, province: "Alberta" },
    };
    renderWithSession(<HotlinesPage />, { profile });
    expect(screen.getByText(/Showing \d+ of \d+ verified resources/i)).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Province or territory"), { target: { value: "AB" } });
    fireEvent.change(screen.getByLabelText("City"), { target: { value: "Edmonton" } });
    fireEvent.change(screen.getByLabelText("Resource type"), { target: { value: "Hotlines" } });
    fireEvent.change(screen.getByLabelText("Search resources"), { target: { value: "zzznomatch999" } });
    expect(screen.getByText("No matching resources")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: /Clear filters/i })[0]);

    // SafeMediaViewer image, video, audio, text, and fallback.
    cleanup();
    const origFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValueOnce({ ok: true, text: async () => "Hello text preview" })
      .mockResolvedValueOnce({ ok: false, text: async () => "" }) as typeof fetch;
    try {
      const baseDoc: BrowserDocument = {
        id: "d1",
        name: "photo.jpg",
        mimeType: "image/jpeg",
        ownerUid: "uid-1",
        createdAt: 1,
        approved: true,
        approvalMessage: "",
        ciphertextSize: 100,
        ciphertextSha256: "a".repeat(64),
        evidence: false,
      };
      const onClose = vi.fn();
      const { rerender } = renderWithSession(<SafeMediaViewer document={baseDoc} url="blob:1" onClose={onClose} />);
      expect(screen.getByAltText("photo.jpg")).toBeTruthy();

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "video/mp4", name: "v.mp4" }} url="blob:2" onClose={onClose} />);
      expect(document.querySelector("video")?.getAttribute("aria-label")).toBe("v.mp4");

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "audio/mp4", name: "a.m4a" }} url="blob:3" onClose={onClose} allowAudioPlayback />);
      expect(document.querySelector("audio")?.getAttribute("aria-label")).toBe("a.m4a");

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "text/plain", name: "t.txt" }} url="blob:4" onClose={onClose} />);
      await waitFor(() => expect(screen.getByText("Hello text preview")).toBeTruthy());

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "text/plain", name: "t2.txt" }} url="blob:5" onClose={onClose} />);
      await waitFor(() => expect(screen.getByText(/Unable to preview this text file/i)).toBeTruthy());

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "application/pdf", name: "doc.pdf" }} url="blob:6" onClose={onClose} />);
      expect(document.querySelector("iframe")?.getAttribute("title")).toBe("doc.pdf");

      rerender(<SafeMediaViewer document={{ ...baseDoc, mimeType: "application/zip", name: "bin.zip" }} url="blob:7" onClose={onClose} />);
      expect(screen.getByText(/cannot be previewed in the browser/i)).toBeTruthy();
    } finally {
      globalThis.fetch = origFetch;
    }

    // WalkthroughPage + WalkthroughCoachmark standalone highlight & placement.
    cleanup();
    const setPage = vi.fn();
    const onStartTour = vi.fn();
    renderWithSession(<WalkthroughPage onStartTour={onStartTour} />, { profile, setPage });
    fireEvent.click(screen.getByTestId("walkthrough-start-tour"));
    expect(onStartTour).toHaveBeenCalled();

    cleanup();
    renderWithSession(<WalkthroughPage />, { profile, setPage });
    fireEvent.click(screen.getByTestId("walkthrough-start-tour"));
    expect(setPage).toHaveBeenCalled();

    // Mount real DOM target with non-zero bounding rect for coachmark measurement.
    cleanup();
    const targetEl = document.createElement("div");
    targetEl.dataset.walkthroughId = "settings-privacy";
    targetEl.getBoundingClientRect = () => ({ left: 20, top: 80, right: 300, bottom: 140, width: 280, height: 60, x: 20, y: 80, toJSON: () => ({}) });
    document.body.appendChild(targetEl);
    const onAdvance = vi.fn();
    renderWithSession(
      <WalkthroughCoachmark
        role="USER"
        activeStepId="settings_privacy_idle"
        onAdvance={onAdvance}
        onOpenContents={vi.fn()}
        onSkip={vi.fn()}
      />,
    );
    expect(screen.getByTestId("walkthrough-target-highlight")).toBeTruthy();
    fireEvent.click(screen.getByTestId("walkthrough-tour-next"));
    expect(onAdvance).toHaveBeenCalled();
    targetEl.remove();
  });
});
