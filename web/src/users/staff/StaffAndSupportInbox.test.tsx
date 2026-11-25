// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyHistory, emptyProfile, type Entry } from "../../domain/DomainModels";
import type { AdminLiveLocation } from "../../services/EmergencyResponseService";
import type { BrowserDocument } from "../../services/MediaService";
import type { ChatMembershipEvent, Conversation, Message } from "../../services/MessagingService";
import { writeSafetyChecklist, type SafetyPlan } from "../../services/SafetyPlanService";
import { installDomMocks, renderWithSession } from "../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "staff-1", username: "staff_one", role: "STAFF" as const } as {
    uid: string;
    username: string;
    role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
  },
  cloudMode: true,
  register: vi.fn(async (..._args: unknown[]) => {}),
  prepareSupportTickets: vi.fn(async () => {}),
  subscribeConversations: vi.fn(async (..._args: unknown[]) => () => {}),
  roomKey: vi.fn(async (..._args: unknown[]) => ({}) as CryptoKey),
  subscribeMessages: vi.fn(async (..._args: unknown[]) => () => {}),
  sendMessage: vi.fn(async (..._args: unknown[]) => {}),
  requestAdministratorHelp: vi.fn(async (..._args: unknown[]) => {}),
  closeSupportTicket: vi.fn(async (..._args: unknown[]) => {}),
  loadBrowserDocumentById: vi.fn(async (..._args: unknown[]): Promise<BrowserDocument> => docFixture),
  observeBrowserDocuments: vi.fn(async (..._args: unknown[]) => () => {}),
  openBrowserDocument: vi.fn(async (..._args: unknown[]) => "blob:staff-doc"),
  loadSharedPrivateEntry: vi.fn(async (..._args: unknown[]): Promise<Entry | null> => null),
  staffConsultations: vi.fn(async () => ({
    available: true,
    plans: [{ ownerUid: "u1", ownerName: "Alex", updatedAt: 1000, ticketNumber: 15, staffUid: "staff-1", staffName: "Staff One" }],
  })),
  setStaffAvailability: vi.fn(async (next: boolean) => ({ available: next })),
  observeSafetyAccess: vi.fn(async (..._args: unknown[]) => () => {}),
  loadPlan: vi.fn(async (..._args: unknown[]): Promise<SafetyPlan> => planFixture()),
  observePlan: vi.fn(async (..._args: unknown[]) => () => {}),
  saveField: vi.fn(async (..._args: unknown[]) => ({ plan: planFixture(), conflicts: [] as string[] })),
  completePlans: vi.fn(async (..._args: unknown[]) => {}),
  observeAdminLiveLocations: vi.fn(async (..._args: unknown[]) => () => {}),
  timelineItems: [] as Array<
    | { kind: "message"; message: Message }
    | { kind: "membership"; event: ChatMembershipEvent }
  >,
}));

const docFixture: BrowserDocument = {
  id: "doc-1",
  name: "id-card.jpg",
  mimeType: "image/jpeg",
  ownerUid: "u1",
  createdAt: 100,
  approved: true,
  approvalMessage: "",
  ciphertextSize: 120,
  ciphertextSha256: "a".repeat(64),
  evidence: false,
};

function planFixture(): SafetyPlan {
  return {
    ownerUid: "u1",
    ownerName: "Alex",
    staffUid: "staff-1",
    consultationRequested: true,
    consultationConversationId: "ticket-1",
    ticketNumber: 15,
    safeContactUids: [],
    fields: {
      primary_documents: {
        value: writeSafetyChecklist([
          { id: "step-1", text: "Bring passport", checked: false, attachmentIds: ["doc-1"] },
          { id: "trusted-contact:1", text: "Sister", checked: true },
        ]),
        revision: 1,
        updatedAt: 100,
      },
    },
    pendingConflicts: {},
  } as unknown as SafetyPlan;
}

vi.mock("../../services/LocalDataStore", () => ({
  database: Promise.resolve({ get: vi.fn(), put: vi.fn(), delete: vi.fn() }),
}));

vi.mock("../../session/SessionProvider", async () => {
  const { useSession } = await import("../../session/SessionContext");
  return { useSession };
});

vi.mock("../../services/AccountService", () => ({
  get cloudMode() {
    return mocks.cloudMode;
  },
  accounts: {
    get identity() {
      return mocks.identity;
    },
  },
}));

vi.mock("../../services/MessagingService", async () => {
  const actual = await vi.importActual<typeof import("../../services/MessagingService")>("../../services/MessagingService");
  return {
    ...actual,
    register: (...args: unknown[]) => mocks.register(...args),
    prepareSupportTickets: () => mocks.prepareSupportTickets(),
    subscribeConversations: (...args: unknown[]) => mocks.subscribeConversations(...args),
    roomKey: (...args: unknown[]) => mocks.roomKey(...args),
    subscribeMessages: (...args: unknown[]) => mocks.subscribeMessages(...args),
    sendMessage: (...args: unknown[]) => mocks.sendMessage(...args),
  };
});

vi.mock("../../services/OrganizationSupportService", () => ({
  requestAdministratorHelp: (...args: unknown[]) => mocks.requestAdministratorHelp(...args),
  closeSupportTicket: (...args: unknown[]) => mocks.closeSupportTicket(...args),
}));

vi.mock("../../services/MediaService", () => ({
  loadBrowserDocumentById: (...args: unknown[]) => mocks.loadBrowserDocumentById(...args),
  observeBrowserDocuments: (...args: unknown[]) => mocks.observeBrowserDocuments(...args),
  openBrowserDocument: (...args: unknown[]) => mocks.openBrowserDocument(...args),
}));

vi.mock("../../services/SharedPrivateEntryService", () => ({
  loadSharedPrivateEntry: (...args: unknown[]) => mocks.loadSharedPrivateEntry(...args),
}));

vi.mock("../../services/SafetyPlanService", async () => {
  const actual = await vi.importActual<typeof import("../../services/SafetyPlanService")>("../../services/SafetyPlanService");
  return {
    ...actual,
    staffConsultations: () => mocks.staffConsultations(),
    setStaffAvailability: (next: boolean) => mocks.setStaffAvailability(next),
    observeSafetyAccess: (...args: unknown[]) => mocks.observeSafetyAccess(...args),
    loadPlan: (...args: unknown[]) => mocks.loadPlan(...args),
    observePlan: (...args: unknown[]) => mocks.observePlan(...args),
    saveField: (...args: unknown[]) => mocks.saveField(...args),
    completePlans: (...args: unknown[]) => mocks.completePlans(...args),
  };
});

vi.mock("../../services/EmergencyResponseService", () => ({
  observeAdminLiveLocations: (...args: unknown[]) => mocks.observeAdminLiveLocations(...args),
}));

vi.mock("../shared/useChatTimeline", () => ({
  useChatDeviceKey: () => false,
  useChatTimeline: () => mocks.timelineItems,
}));

vi.mock("../shared/safe_app/VoiceMessages", () => ({
  VoiceMessageBubble: ({ mediaId }: { mediaId: string }) => <div>VoiceBubble:{mediaId}</div>,
  VoiceMessageComposer: () => <div>VoiceComposerStub</div>,
}));

vi.mock("../../components/BlurredDocumentPreview", () => ({
  BlurredDocumentPreview: ({ documentId }: { documentId?: string }) => <div>Blur:{documentId}</div>,
  NoteAttachmentPreviewGrid: ({ attachmentIds, onOpen }: { attachmentIds?: string[]; onOpen?: (id: string) => void }) => (
    <div>
      {attachmentIds?.map((id) => (
        <button key={id} type="button" onClick={() => onOpen?.(id)}>
          Attach:{id}
        </button>
      ))}
    </div>
  ),
}));

vi.mock("../shared/OrganizationLiveMap", () => ({
  OrganizationLiveMap: ({ locations }: { locations: Record<string, AdminLiveLocation> }) => (
    <div data-testid="staff-map-stub">Locations:{Object.keys(locations).length}</div>
  ),
}));

vi.mock("../shared/OrganizationSettingsPanel", () => ({
  OrganizationSettingsPanel: () => <div data-testid="staff-settings-stub">SettingsStub</div>,
}));

import OrganizationSupportInbox from "../shared/OrganizationSupportInbox";
import StaffPortal from "./StaffDashboardPage";
import { ConsultationWorkspace } from "./ConsultationWorkspace";
import SafetyPlanEditor from "./StaffSafetyPlanEditor";

const supportTicket: Conversation = {
  id: "ticket-1",
  friendshipId: "",
  type: "SUPPORT",
  ownerUid: "u1",
  staffUid: "staff-1",
  adminUid: "",
  supportOrigin: "SUPPORT",
  supportTag: "SUPPORT",
  updatedAt: 1000,
  closedAt: 0,
  ticketNumber: 15,
  participantIds: ["u1", "staff-1"],
  participantUsernames: { u1: "user_one", "staff-1": "staff_one" },
  lastMessage: "Need help",
};

const escalatedTicket: Conversation = {
  ...supportTicket,
  id: "ticket-2",
  supportTag: "NEEDS_ADMIN",
  ticketNumber: 16,
  lastMessage: "Escalated",
};

describe("StaffDashboardPage, ConsultationWorkspace, StaffSafetyPlanEditor, and OrganizationSupportInbox", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.identity = { uid: "staff-1", username: "staff_one", role: "STAFF" };
    mocks.cloudMode = true;
    mocks.subscribeConversations.mockImplementation(async (_uid, cb) => {
      (cb as (c: Conversation[]) => void)([
        supportTicket,
        escalatedTicket,
        { ...supportTicket, id: "ticket-closed", closedAt: 500, ticketNumber: 14 },
      ]);
      return () => {};
    });
    mocks.subscribeMessages.mockImplementation(async (_id, _key, cb) => {
      (cb as (m: Message[]) => void)([{ id: "m1", senderUid: "u1", senderUsername: "user_one", body: "Hi", deleted: false, clientSentAt: 100 }]);
      return () => {};
    });
    mocks.timelineItems = [
      { kind: "message", message: { id: "m1", senderUid: "u1", senderUsername: "user_one", body: "Hello staff", deleted: false, clientSentAt: 100 } },
      { kind: "message", message: { id: "m2", senderUid: "u1", senderUsername: "user_one", body: "SHARED_MEDIA:doc-1:id-card.jpg", deleted: false, clientSentAt: 200 } },
      { kind: "message", message: { id: "m3", senderUid: "u1", senderUsername: "user_one", body: "SHARED_INCIDENT:inc-1:Incident", deleted: false, clientSentAt: 300 } },
      { kind: "message", message: { id: "m4", senderUid: "u1", senderUsername: "user_one", body: "SHARED_VOICE:v-1:Voice", deleted: false, clientSentAt: 400 } },
      {
        kind: "membership",
        event: { id: "ev1", action: "ADDED", actorUid: "u1", actorName: "user_one", participantUid: "staff-1", participantName: "staff_one", createdAt: 150 },
      },
    ];
    mocks.observeBrowserDocuments.mockImplementation(async (_id, cb) => {
      (cb as (d: BrowserDocument[]) => void)([docFixture]);
      return () => {};
    });
    mocks.observeAdminLiveLocations.mockImplementation(async (onData) => {
      (onData as (p: Record<string, AdminLiveLocation>) => void)({
        u1: { uid: "u1", name: "Alex", latitude: 53.5, longitude: -113.5, accuracy: 10, active: true, updatedAt: 100 },
      });
      return () => {};
    });
  });

  afterEach(() => {
    cleanup();
  });

  it("drives OrganizationSupportInbox for Staff and Admin, including shared previews, escalate, reply, close, and offline guard", async () => {
    mocks.loadSharedPrivateEntry.mockResolvedValueOnce({
      id: "inc-1",
      timestamp: 100,
      updatedAt: 100,
      kind: "Incident",
      title: "Incident Report",
      body: "Summary",
      incidentDate: "2026-11-24",
      attachmentIds: ["doc-1"],
      pinned: false,
      edits: [],
    });

    renderWithSession(<OrganizationSupportInbox />);
    await waitFor(() => expect(screen.getByText("Support Ticket #15")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /Support Ticket #15/i }));
    await waitFor(() => expect(screen.getByText("Hello staff")).toBeTruthy());

    // Open shared media & shared incident entry modals.
    fireEvent.click(screen.getByText("id-card.jpg").closest("button")!);
    await waitFor(() => expect(screen.getByAltText("id-card.jpg")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    fireEvent.click(screen.getByText("Incident").closest("button")!);
    await waitFor(() => expect(screen.getByText("Incident Report")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    // Send encrypted reply, request admin help, reopen ticket and close for everyone.
    fireEvent.change(screen.getByLabelText("Write a secure reply"), { target: { value: "Helping now" } });
    fireEvent.submit(screen.getByRole("button", { name: "Send reply" }).closest("form")!);
    await waitFor(() => expect(mocks.sendMessage).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: /Request admin help/i }));
    await waitFor(() => expect(mocks.requestAdministratorHelp).toHaveBeenCalledWith("ticket-1"));

    fireEvent.click(screen.getByRole("button", { name: "Back to tickets" }));
    fireEvent.click(screen.getByRole("button", { name: /Admin Ticket #16/i }));
    fireEvent.click(screen.getByRole("button", { name: /Close for everyone/i }));
    await waitFor(() => expect(mocks.closeSupportTicket).toHaveBeenCalledWith("ticket-2"));

    // Toggle archived view.
    fireEvent.click(screen.getByRole("button", { name: "Archived" }));
    expect(screen.getByText(/Support Ticket #14/i)).toBeTruthy();

    // Admin mode + offline guard.
    cleanup();
    mocks.identity = { uid: "admin-1", username: "admin_one", role: "ADMIN" };
    renderWithSession(<OrganizationSupportInbox />);
    await waitFor(() => expect(screen.getByLabelText("Escalated ticket category")).toBeTruthy());

    cleanup();
    mocks.cloudMode = false;
    renderWithSession(<OrganizationSupportInbox />);
    expect(screen.getByText("Support inbox needs a shared account.")).toBeTruthy();
  });

  it("drives StaffSafetyPlanEditor and ConsultationWorkspace (STAFF and ADMIN modes)", async () => {
    const onChange = vi.fn();
    const onCompleted = vi.fn();
    const plan = planFixture();

    renderWithSession(<SafetyPlanEditor plan={plan} onChange={onChange} onCompleted={onCompleted} />);
    await waitFor(() => expect(screen.getByText("id-card.jpg")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Backup plan" }));
    fireEvent.click(screen.getByRole("button", { name: "Primary plan" }));

    const input = screen.getByLabelText("primary documents step");
    fireEvent.change(input, { target: { value: "Bring passport copy" } });
    fireEvent.blur(input);
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    fireEvent.click(screen.getByLabelText("Complete Bring passport"));
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "id-card.jpg" }));
    await waitFor(() => expect(screen.getByAltText("id-card.jpg")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    fireEvent.click(screen.getAllByRole("button", { name: "Add step" })[0]);
    await waitFor(() => expect(mocks.saveField).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Mark complete" }));
    await waitFor(() => expect(mocks.completePlans).toHaveBeenCalledWith("ticket-1"));
    expect(onCompleted).toHaveBeenCalled();

    // ConsultationWorkspace ADMIN grouped mode + Back button.
    cleanup();
    const onOpen = vi.fn();
    renderWithSession(
      <ConsultationWorkspace
        role="ADMIN"
        items={[
          { ownerUid: "u1", updatedAt: 100, ticketNumber: 15, staffUid: "staff-1", staffName: "Staff One" },
          { ownerUid: "u2", updatedAt: 200, ticketNumber: 18 },
        ]}
        plan={plan}
        busy={false}
        onOpen={onOpen}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Assigned to staff/i }));
    fireEvent.click(screen.getByRole("button", { name: /Assigned to staff/i }));
    fireEvent.click(screen.getByRole("button", { name: /Consult Ticket #18/i }));
    expect(onOpen).toHaveBeenCalledWith("u2");
    fireEvent.click(screen.getByRole("button", { name: /Back to consultations/i }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("drives StaffDashboardPage availability toggle and views", async () => {
    renderWithSession(<StaffPortal />, {
      profile: { ...emptyProfile("staff_one", "STAFF"), fullName: "Staff One" },
      history: emptyHistory(),
    });

    await waitFor(() => expect(screen.getByText(/Available for new requests/i)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Pause availability" }));
    await waitFor(() => expect(mocks.setStaffAvailability).toHaveBeenCalledWith(false));

    fireEvent.click(screen.getByRole("button", { name: "Consultations" }));
    fireEvent.click(screen.getByRole("button", { name: /Consult Ticket #15/i }));
    await waitFor(() => expect(mocks.loadPlan).toHaveBeenCalledWith("u1"));

    fireEvent.click(screen.getByRole("button", { name: "Support chats" }));
    await waitFor(() => expect(screen.getByText("Ticket inbox")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Live map" }));
    await waitFor(() => expect(screen.getByTestId("staff-map-stub").textContent).toBe("Locations:1"));

    fireEvent.click(screen.getByRole("button", { name: "Settings" }));
    expect(screen.getByTestId("staff-settings-stub")).toBeTruthy();
  });
});
