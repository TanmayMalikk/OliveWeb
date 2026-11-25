// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyProfile } from "../../../domain/DomainModels";
import type { BrowserDocument } from "../../../services/MediaService";
import type { Friend } from "../../../services/MessagingService";
import { writeSafetyChecklist, type SafetyPlan as Plan } from "../../../services/SafetyPlanService";
import { installDomMocks, renderWithSession } from "../../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "uid-1", username: "user_one", role: "USER" as const } as {
    uid: string;
    username: string;
    role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
  },
  cloudMode: true,
  loadPlan: vi.fn(async (..._args: unknown[]): Promise<Plan> => makePlan()),
  observePlan: vi.fn(async (..._args: unknown[]) => () => {}),
  observeSafetyAccess: vi.fn(async (..._args: unknown[]) => () => {}),
  saveField: vi.fn(async (..._args: unknown[]): Promise<{ plan: Plan; conflicts: string[]; conflictValues?: Record<string, string> }> => ({ plan: makePlan(), conflicts: [] })),
  consultation: vi.fn(async (..._args: unknown[]): Promise<Plan> => makePlan()),
  sharedPlans: vi.fn(async () => []),
  sharePlanWithContacts: vi.fn(async (..._args: unknown[]): Promise<Plan> => makePlan()),
  resolvePlanConflict: vi.fn(async (..._args: unknown[]): Promise<Plan> => makePlan()),
  planMediaRecipients: vi.fn(async () => ["uid-staff"]),
  observeBrowserDocuments: vi.fn(async (..._args: unknown[]) => () => {}),
  observeDocumentRecipientDevices: vi.fn(async (..._args: unknown[]) => () => {}),
  listBrowserDocuments: vi.fn(async (..._args: unknown[]): Promise<BrowserDocument[]> => []),
  openBrowserDocument: vi.fn(async (..._args: unknown[]) => "blob:doc-open"),
  shareBrowserPlanDocument: vi.fn(async (..._args: unknown[]) => {}),
  uploadBrowserDocument: vi.fn(async (..._args: unknown[]) => "doc-uploaded"),
  setBrowserDocumentEvidence: vi.fn(async (..._args: unknown[]) => {}),
  subscribeSocial: vi.fn(async (..._args: unknown[]) => () => {}),
  prepareSupportTickets: vi.fn(async () => {}),
  subscribeConversations: vi.fn(async (..._args: unknown[]) => () => {}),
  callableMock: vi.fn(),
}));

vi.mock("../../../session/SessionProvider", async () => {
  const { useSession } = await import("../../../session/SessionContext");
  return { useSession };
});

vi.mock("../../../services/AccountService", () => ({
  get cloudMode() {
    return mocks.cloudMode;
  },
  accounts: {
    get identity() {
      return mocks.identity;
    },
  },
  firebase: vi.fn(async () => ({ functions: {} })),
}));

vi.mock("firebase/functions", () => ({
  httpsCallable: () => mocks.callableMock,
}));

vi.mock("../../../services/SafetyPlanService", async () => {
  const actual = await vi.importActual<typeof import("../../../services/SafetyPlanService")>("../../../services/SafetyPlanService");
  return {
    ...actual,
    loadPlan: (...args: unknown[]) => mocks.loadPlan(...args),
    observePlan: (...args: unknown[]) => mocks.observePlan(...args),
    observeSafetyAccess: (...args: unknown[]) => mocks.observeSafetyAccess(...args),
    saveField: (...args: unknown[]) => mocks.saveField(...args),
    consultation: (...args: unknown[]) => mocks.consultation(...args),
    sharedPlans: () => mocks.sharedPlans(),
    sharePlanWithContacts: (...args: unknown[]) => mocks.sharePlanWithContacts(...args),
    resolvePlanConflict: (...args: unknown[]) => mocks.resolvePlanConflict(...args),
    planMediaRecipients: () => mocks.planMediaRecipients(),
  };
});

vi.mock("../../../services/MediaService", () => ({
  observeBrowserDocuments: (...args: unknown[]) => mocks.observeBrowserDocuments(...args),
  observeDocumentRecipientDevices: (...args: unknown[]) => mocks.observeDocumentRecipientDevices(...args),
  listBrowserDocuments: (...args: unknown[]) => mocks.listBrowserDocuments(...args),
  openBrowserDocument: (...args: unknown[]) => mocks.openBrowserDocument(...args),
  shareBrowserPlanDocument: (...args: unknown[]) => mocks.shareBrowserPlanDocument(...args),
  uploadBrowserDocument: (...args: unknown[]) => mocks.uploadBrowserDocument(...args),
  setBrowserDocumentEvidence: (...args: unknown[]) => mocks.setBrowserDocumentEvidence(...args),
}));

vi.mock("../../../services/MessagingService", () => ({
  subscribeSocial: (...args: unknown[]) => mocks.subscribeSocial(...args),
  prepareSupportTickets: () => mocks.prepareSupportTickets(),
  subscribeConversations: (...args: unknown[]) => mocks.subscribeConversations(...args),
}));

vi.mock("../FadedMap", () => ({
  FadedMap: ({ label, current }: { label: string; current?: { latitude: number; longitude: number } }) => (
    <div data-testid="faded-map-stub">{label}:{current?.latitude},{current?.longitude}</div>
  ),
}));

vi.mock("../../../components/BlurredDocumentPreview", () => ({
  BlurredDocumentPreview: ({ document }: { document: BrowserDocument }) => (
    <div data-testid={`blurred-${document.id}`}>{document.name}</div>
  ),
}));

import SafetyPlan from "./SafetyPlanPage";
import { DocumentsPage, LocationPage, StaffHelpPage } from "./SupportAndLocationPages";

const docFixture: BrowserDocument = {
  id: "doc-1",
  name: "id-scan.jpg",
  mimeType: "image/jpeg",
  ownerUid: "uid-1",
  createdAt: 1000,
  approved: true,
  approvalMessage: "",
  ciphertextSize: 120,
  ciphertextSha256: "a".repeat(64),
  evidence: false,
};

function makePlan(): Plan {
  return {
    ownerUid: "uid-1",
    ownerName: "Alex",
    safeContactUids: ["friend-1"],
    staffUid: "staff-1",
    consultationRequested: false,
    consultationConversationId: "ticket-1",
    plansCompletedAt: 500,
    fields: {
      primary_documents: {
        value: writeSafetyChecklist([{ id: "step-1", text: "Passport", checked: false, attachmentIds: ["doc-1"] }]),
        revision: 1,
        updatedAt: 100,
      },
      primary_grab: {
        value: writeSafetyChecklist([{ id: "trusted-contact:1", text: "Call friend", checked: true }]),
        revision: 1,
        updatedAt: 100,
      },
    },
    pendingConflicts: {},
  } as unknown as Plan;
}

describe("SafetyPlanPage, StaffHelpPage, LocationPage, and DocumentsPage", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.identity = { uid: "uid-1", username: "user_one", role: "USER" };
    mocks.cloudMode = true;
    const plan = makePlan();
    mocks.loadPlan.mockResolvedValue(plan);
    mocks.observePlan.mockImplementation(async (_uid, cb) => {
      (cb as (p: Plan) => void)(plan);
      return () => {};
    });
    mocks.saveField.mockImplementation(async (pArg, keyArg, valArg) => {
      const p = pArg as Plan;
      const key = keyArg as string;
      const value = valArg as string;
      return {
        plan: { ...p, fields: { ...p.fields, [key]: { value, revision: 2, updatedAt: 200 } } },
        conflicts: [],
      };
    });
    mocks.observeBrowserDocuments.mockImplementation(async (_id, cb) => {
      (cb as (docs: BrowserDocument[]) => void)([
        docFixture,
        { ...docFixture, id: "doc-2", name: "lease.pdf", mimeType: "application/pdf", approved: false, approvalMessage: "Pending" },
      ]);
      return () => {};
    });
    mocks.listBrowserDocuments.mockResolvedValue([docFixture]);
    mocks.subscribeSocial.mockImplementation(async (_uid, cb) => {
      (cb as (v: { friends: Friend[] }) => void)({ friends: [{ friendshipId: "f1", friendUid: "friend-1", friendUsername: "sam" }] });
      return () => {};
    });
  });

  afterEach(() => {
    cleanup();
  });

  it("drives SafetyPlanPage editing, attachments, consultation, sharing, conflicts, and SAFE_CONTACT view", async () => {
    const setPage = vi.fn();
    const setRequestedConversation = vi.fn();
    const profile = { ...emptyProfile("user_one", "USER"), affectedNames: "Child One" };

    renderWithSession(<SafetyPlan />, { profile, setPage, setRequestedConversation });
    await waitFor(() => expect(screen.getByDisplayValue("Passport")).toBeTruthy());

    // Tab switching & jump links.
    const primaryTab = screen.getByRole("tab", { name: "Primary plan" });
    fireEvent.keyDown(primaryTab, { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: "Backup plan" }).getAttribute("aria-selected")).toBe("true");
    fireEvent.click(primaryTab);
    fireEvent.click(screen.getByRole("button", { name: "Quick-grab" }));

    // Edit checklist item text & blur save, toggle checkbox, add/remove step.
    const stepInput = screen.getByLabelText("Important documents step 1");
    fireEvent.change(stepInput, { target: { value: "Passport & birth certificate" } });
    fireEvent.blur(stepInput);
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    const checkbox = screen.getByLabelText(/Passport & birth certificate, unchecked/i);
    fireEvent.click(checkbox);
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    // Open document preview in SafeMediaViewer, attach another saved doc, upload new file, remove attachment.
    fireEvent.click(screen.getByRole("button", { name: "id-scan.jpg" }));
    await waitFor(() => expect(screen.getByAltText("id-scan.jpg")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    fireEvent.change(screen.getByLabelText("Attach a saved document to step 1"), { target: { value: "doc-2" } });
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    const uploadInput = screen.getByLabelText("Upload a document for step 1") as HTMLInputElement;
    const file = new File(["pdf"], "extra.pdf", { type: "application/pdf" });
    Object.defineProperty(uploadInput, "files", { configurable: true, value: [file] });
    fireEvent.change(uploadInput);
    await waitFor(() => expect(mocks.uploadBrowserDocument).toHaveBeenCalled());

    fireEvent.click(screen.getByLabelText("Remove attachment id-scan.jpg"));
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    // Add & remove step.
    fireEvent.click(screen.getAllByRole("button", { name: "Add step" })[0]);
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());
    fireEvent.click(screen.getByLabelText("Remove Important documents step 2"));
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    // Share with safe contacts (toggle checkbox + save) & consultation ticket button.
    fireEvent.click(screen.getByLabelText("sam"));
    fireEvent.click(screen.getByLabelText("sam"));
    mocks.sharePlanWithContacts.mockResolvedValueOnce(makePlan());
    fireEvent.click(screen.getByRole("button", { name: "Save sharing" }));
    await waitFor(() => expect(mocks.sharePlanWithContacts).toHaveBeenCalled());

    mocks.consultation.mockResolvedValueOnce({
      ...makePlan(),
      consultationRequested: true,
      consultationConversationId: "ticket-9",
    });
    fireEvent.click(screen.getByRole("button", { name: "Request consultation" }));
    await waitFor(() => expect(setPage).toHaveBeenCalledWith("Messages"));
    const openTicketBtn = screen.queryByRole("button", { name: "Open consultation ticket" });
    if (openTicketBtn) fireEvent.click(openTicketBtn);

    // Local conflict modal: toggle "Your change" and resolve via saveField.
    const conflictInput = screen.getByLabelText("Important documents step 1");
    mocks.saveField.mockResolvedValueOnce({
      plan: makePlan(),
      conflicts: ["primary_documents"],
      conflictValues: { primary_documents: writeSafetyChecklist([{ id: "step-1", text: "My local draft", checked: false }]) },
    });
    fireEvent.change(conflictInput, { target: { value: "My local draft" } });
    fireEvent.blur(conflictInput);
    await waitFor(() => expect(screen.getByText("Conflicts")).toBeTruthy());
    fireEvent.click(screen.getByLabelText(/Your change/i));
    fireEvent.click(screen.getByRole("button", { name: "Keep my change", hidden: true }));
    await waitFor(() => expect(screen.queryByText("Conflicts")).toBeNull());

    // Conflict resolution modal.
    const conflictPlan: Plan = {
      ...makePlan(),
      pendingConflicts: {
        primary_grab: {
          value: writeSafetyChecklist([{ id: "c1", text: "Server item", checked: false }]),
          mutationId: "m1",
          updatedAt: 999,
        } as unknown as NonNullable<Plan["pendingConflicts"]>[string],
      },
    };
    mocks.loadPlan.mockResolvedValueOnce(conflictPlan);
    mocks.observePlan.mockResolvedValueOnce(() => {});
    mocks.resolvePlanConflict.mockResolvedValueOnce(makePlan());
    cleanup();
    renderWithSession(<SafetyPlan />, { profile });
    await waitFor(() => expect(screen.getByText("Conflicts")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /Use incoming change/i, hidden: true }));
    await waitFor(() => expect(mocks.resolvePlanConflict).toHaveBeenCalled());

    // SAFE_CONTACT read-only view across shared plans.
    cleanup();
    mocks.identity = { uid: "friend-1", username: "sam", role: "SAFE_CONTACT" };
    mocks.sharedPlans.mockResolvedValueOnce([
      { ownerUid: "uid-1", ownerName: "Alex" },
      { ownerUid: "uid-2", ownerName: "Jamie" },
    ] as unknown as never[]);
    mocks.loadPlan.mockResolvedValue(makePlan());
    renderWithSession(<SafetyPlan />, { profile: emptyProfile("friend-1", "SAFE_CONTACT") });
    await waitFor(() => expect(screen.getByText(/Alex’s safety plan/i)).toBeTruthy());
    fireEvent.change(screen.getByLabelText("Choose a shared plan"), { target: { value: "uid-2" } });

    // Error observer, loadPlan rejection, and local-mode empty state.
    cleanup();
    mocks.sharedPlans.mockResolvedValueOnce([{ ownerUid: "uid-1", ownerName: "Alex" }] as unknown as never[]);
    mocks.observePlan.mockImplementationOnce(async (_uid, _cb, onErr) => {
      (onErr as (e: Error) => void)(new Error("Stream interrupted"));
      return () => {};
    });
    renderWithSession(<SafetyPlan />, { profile: emptyProfile("friend-1", "SAFE_CONTACT") });
    await waitFor(() => expect(screen.getByText("Stream interrupted")).toBeTruthy());

    cleanup();
    mocks.sharedPlans.mockResolvedValueOnce([{ ownerUid: "uid-1", ownerName: "Alex" }] as unknown as never[]);
    mocks.loadPlan.mockRejectedValueOnce(new Error("Plan blocked"));
    renderWithSession(<SafetyPlan />, { profile: emptyProfile("friend-1", "SAFE_CONTACT") });
    await waitFor(() => expect(screen.getByText("Plan blocked")).toBeTruthy());

    cleanup();
    mocks.cloudMode = false;
    renderWithSession(<SafetyPlan />, { profile });
    expect(screen.getByText("Safety plans use your shared account.")).toBeTruthy();
  });

  it("drives StaffHelpPage, LocationPage, and DocumentsPage", async () => {
    const setPage = vi.fn();
    const setRequestedConversation = vi.fn();

    // StaffHelpPage: request & cancel & open ticket.
    mocks.subscribeConversations.mockImplementation(async (_uid, cb) => {
      (cb as (convs: unknown[]) => void)([]);
      return () => {};
    });
    mocks.callableMock
      .mockResolvedValueOnce({ data: { ticket: { requested: true, conversationId: "t-1", ticketNumber: 42 } } })
      .mockResolvedValueOnce({ data: { ticket: { requested: false } } });

    renderWithSession(<StaffHelpPage />, { setPage, setRequestedConversation });
    fireEvent.click(screen.getByRole("button", { name: "Request help from staff" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: /Support Ticket #42/i })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /Support Ticket #42/i }));
    expect(setRequestedConversation).toHaveBeenCalledWith({ id: "t-1", uid: "uid-1" });
    expect(setPage).toHaveBeenCalledWith("Messages");
    fireEvent.click(screen.getByRole("button", { name: "Cancel request" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Request help from staff" })).toBeTruthy());

    // LocationPage: geolocation success & error branches.
    cleanup();
    const getCurrentPosition = vi
      .fn()
      .mockImplementationOnce((ok: (p: { coords: { latitude: number; longitude: number; accuracy: number } }) => void) =>
        ok({ coords: { latitude: 53.5461, longitude: -113.4938, accuracy: 12 } }),
      )
      .mockImplementationOnce((_ok: unknown, err: () => void) => err());
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition },
    });
    renderWithSession(<LocationPage />);
    await waitFor(() => expect(screen.getByText(/Accuracy about 12 m/i)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Update location" }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());

    // DocumentsPage: list, upload, toggle evidence, open preview, refresh error.
    cleanup();
    renderWithSession(<DocumentsPage />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "id-scan.jpg" })).toBeTruthy());
    fireEvent.click(screen.getAllByLabelText("Evidence")[0]);
    await waitFor(() => expect(mocks.setBrowserDocumentEvidence).toHaveBeenCalledWith("doc-1", true));

    fireEvent.click(screen.getByRole("button", { name: "Open document" }));
    await waitFor(() => expect(screen.getByAltText("id-scan.jpg")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    const docUploadInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    const imgFile = new File(["img"], "snap.png", { type: "image/png" });
    Object.defineProperty(docUploadInput, "files", { configurable: true, value: [imgFile] });
    fireEvent.change(docUploadInput);
    await waitFor(() => expect(mocks.uploadBrowserDocument).toHaveBeenCalled());

    mocks.listBrowserDocuments.mockRejectedValueOnce(new Error("Sync down"));
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(screen.getByText("Sync down")).toBeTruthy());
  });
});
