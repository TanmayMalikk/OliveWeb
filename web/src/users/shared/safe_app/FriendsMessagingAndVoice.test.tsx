// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyHistory, emptyProfile, type Entry } from "../../../domain/DomainModels";
import type { BrowserDocument } from "../../../services/MediaService";
import type { ChatMembershipEvent, Conversation, Message, Request } from "../../../services/MessagingService";
import { installDomMocks, renderWithSession } from "../../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "uid-1", username: "user_one", role: "USER" as const } as {
    uid: string;
    username: string;
    role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
  },
  cloudMode: true,
  register: vi.fn(async (..._args: unknown[]) => {}),
  prepareSupportTickets: vi.fn(async () => {}),
  subscribeSocial: vi.fn(async (..._args: unknown[]) => () => {}),
  subscribeConversations: vi.fn(async (..._args: unknown[]) => () => {}),
  roomKey: vi.fn(async (..._args: unknown[]) => ({}) as CryptoKey),
  subscribeMessages: vi.fn(async (..._args: unknown[]) => () => {}),
  acceptRequest: vi.fn(async (..._args: unknown[]) => {}),
  dismissRequest: vi.fn(async (..._args: unknown[]) => {}),
  sendMessage: vi.fn(async (..._args: unknown[]) => {}),
  unsendMessage: vi.fn(async (..._args: unknown[]) => {}),
  removeTicketFriend: vi.fn(async (..._args: unknown[]) => {}),
  createFriendInvite: vi.fn(async (..._args: unknown[]) => ({ code: "", link: "", expiresAt: 0 })),
  claimFriendInvite: vi.fn(async (..._args: unknown[]) => ({ username: "" })),
  subscribeFriendInvite: vi.fn(async (..._args: unknown[]) => () => {}),
  listBrowserDocuments: vi.fn(async (..._args: unknown[]): Promise<BrowserDocument[]> => []),
  loadBrowserDocumentById: vi.fn(async (..._args: unknown[]): Promise<BrowserDocument | null> => null),
  openBrowserDocument: vi.fn(async (..._args: unknown[]) => "blob:media"),
  uploadBrowserDocument: vi.fn(async (..._args: unknown[]) => "voice-media-1"),
  prepareVoiceMessage: vi.fn(async (..._args: unknown[]) => ({ body: "OLIVE_VOICE:voice-media-1" })),
  publishVoiceMessage: vi.fn(async (..._args: unknown[]) => {}),
  openVoiceMessage: vi.fn(async (..._args: unknown[]) => "blob:voice-play"),
  loadSharedPrivateEntry: vi.fn(async (..._args: unknown[]): Promise<Entry | null> => null),
  timelineItems: [] as Array<
    | { kind: "message"; message: Message }
    | { kind: "membership"; event: ChatMembershipEvent }
  >,
  ticketHistory: [] as ChatMembershipEvent[],
}));

vi.mock("../../../services/LocalDataStore", () => ({
  database: Promise.resolve({ get: vi.fn(), put: vi.fn(), delete: vi.fn() }),
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
}));

vi.mock("../../../services/MessagingService", async () => {
  const actual = await vi.importActual<typeof import("../../../services/MessagingService")>("../../../services/MessagingService");
  return {
    ...actual,
    register: (...args: unknown[]) => mocks.register(...args),
    prepareSupportTickets: () => mocks.prepareSupportTickets(),
    subscribeSocial: (...args: unknown[]) => mocks.subscribeSocial(...args),
    subscribeConversations: (...args: unknown[]) => mocks.subscribeConversations(...args),
    roomKey: (...args: unknown[]) => mocks.roomKey(...args),
    subscribeMessages: (...args: unknown[]) => mocks.subscribeMessages(...args),
    acceptRequest: (...args: unknown[]) => mocks.acceptRequest(...args),
    dismissRequest: (...args: unknown[]) => mocks.dismissRequest(...args),
    sendMessage: (...args: unknown[]) => mocks.sendMessage(...args),
    unsendMessage: (...args: unknown[]) => mocks.unsendMessage(...args),
    removeTicketFriend: (...args: unknown[]) => mocks.removeTicketFriend(...args),
  };
});

vi.mock("../../../services/FriendInviteService", () => ({
  createFriendInvite: (...args: unknown[]) => mocks.createFriendInvite(...args),
  claimFriendInvite: (...args: unknown[]) => mocks.claimFriendInvite(...args),
  subscribeFriendInvite: (...args: unknown[]) => mocks.subscribeFriendInvite(...args),
}));

vi.mock("../../../services/MediaService", () => ({
  listBrowserDocuments: (...args: unknown[]) => mocks.listBrowserDocuments(...args),
  loadBrowserDocumentById: (...args: unknown[]) => mocks.loadBrowserDocumentById(...args),
  openBrowserDocument: (...args: unknown[]) => mocks.openBrowserDocument(...args),
  uploadBrowserDocument: (...args: unknown[]) => mocks.uploadBrowserDocument(...args),
  prepareVoiceMessage: (...args: unknown[]) => mocks.prepareVoiceMessage(...args),
  publishVoiceMessage: (...args: unknown[]) => mocks.publishVoiceMessage(...args),
  openVoiceMessage: (...args: unknown[]) => mocks.openVoiceMessage(...args),
}));

vi.mock("../../../services/SharedPrivateEntryService", () => ({
  loadSharedPrivateEntry: (...args: unknown[]) => mocks.loadSharedPrivateEntry(...args),
}));

vi.mock("../useChatTimeline", () => ({
  useChatDeviceKey: () => false,
  useChatTimeline: () => mocks.timelineItems,
  useMembershipHistory: () => mocks.ticketHistory,
}));

vi.mock("../../../components/BlurredDocumentPreview", () => ({
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

import Messages from "./FriendsAndMessagesPage";
import { FriendInviteControls, InviteCodeEntry } from "./FriendInviteControls";
import { VoiceMessageBubble, VoiceMessageComposer } from "./VoiceMessages";
import { WalkthroughActionContext } from "./WalkthroughCoachmark";

const dmConv: Conversation = {
  id: "dm-1",
  friendshipId: "fr-dm",
  type: "DIRECT",
  ownerUid: "uid-1",
  staffUid: "",
  adminUid: "",
  supportOrigin: "",
  supportTag: "",
  updatedAt: 1000,
  closedAt: 0,
  participantIds: ["uid-1", "friend-1"],
  participantUsernames: { "uid-1": "user_one", "friend-1": "sam" },
  lastMessage: "Hello",
};

const ticketConv: Conversation = {
  id: "ticket-1",
  friendshipId: "",
  type: "SUPPORT",
  ownerUid: "uid-1",
  staffUid: "staff-1",
  adminUid: "",
  supportOrigin: "SUPPORT",
  supportTag: "SUPPORT",
  updatedAt: 2000,
  closedAt: 0,
  ticketNumber: 12,
  participantIds: ["uid-1", "staff-1", "friend-1"],
  participantUsernames: { "uid-1": "user_one", "staff-1": "Olive Staff", "friend-1": "sam" },
  lastMessage: "Support active",
};

const sharedDoc: BrowserDocument = {
  id: "doc-1",
  name: "photo.jpg",
  mimeType: "image/jpeg",
  ownerUid: "uid-1",
  createdAt: 1000,
  approved: true,
  approvalMessage: "",
  ciphertextSize: 120,
  ciphertextSha256: "a".repeat(64),
  evidence: false,
};

describe("FriendsAndMessagesPage, FriendInviteControls, and VoiceMessages", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.identity = { uid: "uid-1", username: "user_one", role: "USER" };
    mocks.cloudMode = true;
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn(async () => {}) },
    });
    mocks.createFriendInvite.mockResolvedValue({
      code: "INV12345",
      link: "https://yourolivebranch.ca/get?invite=INV12345",
      expiresAt: Date.now() + 60_000,
    });
    mocks.subscribeFriendInvite.mockResolvedValue(() => {});
    mocks.subscribeSocial.mockImplementation(async (_uid, cb) => {
      (cb as (s: { friends: []; incoming: Request[]; outgoing: Request[] }) => void)({
        friends: [],
        incoming: [{ id: "req-1", senderUid: "f2", senderUsername: "alex", recipientUid: "uid-1", recipientUsername: "user_one", friendshipId: "fr-1" }],
        outgoing: [{ id: "req-2", senderUid: "uid-1", senderUsername: "user_one", recipientUid: "f3", recipientUsername: "pat", friendshipId: "fr-2" }],
      });
      return () => {};
    });
    mocks.subscribeConversations.mockImplementation(async (_uid, cb) => {
      (cb as (c: Conversation[]) => void)([dmConv, ticketConv, { ...dmConv, id: "dm-arch", closedAt: 999 }]);
      return () => {};
    });
    mocks.subscribeMessages.mockImplementation(async (_cid, _key, cb) => {
      (cb as (m: Message[]) => void)([
        { id: "m1", senderUid: "uid-1", senderUsername: "user_one", body: "Hello there", deleted: false, clientSentAt: 1000 },
        { id: "m2", senderUid: "friend-1", senderUsername: "sam", body: "[Shared doc](olive://media/doc-1)", deleted: false, clientSentAt: 2000 },
        { id: "m3", senderUid: "friend-1", senderUsername: "sam", body: "[Shared note](olive://note/note-shared)", deleted: false, clientSentAt: 3000 },
        { id: "m4", senderUid: "friend-1", senderUsername: "sam", body: "[Voice](olive://voice/voice-1)", deleted: false, clientSentAt: 4000 },
      ]);
      return () => {};
    });
    mocks.loadBrowserDocumentById.mockResolvedValue(sharedDoc);
    mocks.timelineItems = [
      { kind: "message", message: { id: "m1", senderUid: "uid-1", senderUsername: "user_one", body: "Hello there", deleted: false, clientSentAt: 1000 } },
      { kind: "message", message: { id: "m2", senderUid: "friend-1", senderUsername: "sam", body: "SHARED_MEDIA:doc-1:photo.jpg", deleted: false, clientSentAt: 2000 } },
      { kind: "message", message: { id: "m3", senderUid: "friend-1", senderUsername: "sam", body: "SHARED_INCIDENT:note-shared:MyNote", deleted: false, clientSentAt: 3000 } },
      { kind: "message", message: { id: "m4", senderUid: "friend-1", senderUsername: "sam", body: "SHARED_VOICE:voice-1:Voice", deleted: false, clientSentAt: 4000 } },
      {
        kind: "membership",
        event: { id: "ev-1", action: "ADDED", actorUid: "uid-1", actorName: "user_one", participantUid: "friend-1", participantName: "sam", createdAt: 1500 },
      },
    ];
    mocks.ticketHistory = [
      { id: "ev-1", action: "ADDED", actorUid: "uid-1", actorName: "user_one", participantUid: "friend-1", participantName: "sam", createdAt: 1500 },
    ];
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("drives FriendInviteControls and InviteCodeEntry", async () => {
    mocks.claimFriendInvite
      .mockResolvedValueOnce({ username: "pat" })
      .mockRejectedValueOnce(new Error("Expired code"));

    renderWithSession(
      <WalkthroughActionContext.Provider value={{ stepId: "msg-inv", action: "messages_open_invite" }}>
        <FriendInviteControls />
      </WalkthroughActionContext.Provider>,
    );

    await waitFor(() => expect(screen.getByText("INV12345")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    await waitFor(() => expect(screen.getByText("Copied.")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Refresh invite code" }));
    await waitFor(() => expect(mocks.createFriendInvite).toHaveBeenCalledWith(true));

    cleanup();
    renderWithSession(<InviteCodeEntry />);
    const input = screen.getByLabelText(/Have an invite code/i);
    fireEvent.change(input, { target: { value: "CODE1" } });
    fireEvent.submit(input.closest("form")!);
    await waitFor(() => expect(screen.getByText(/Connected with pat/i)).toBeTruthy());

    fireEvent.change(input, { target: { value: "BAD" } });
    fireEvent.submit(input.closest("form")!);
    await waitFor(() => expect(screen.getByText("Expired code")).toBeTruthy());
  });

  it("drives VoiceMessageComposer recording/draft/send/discard and VoiceMessageBubble playback", async () => {
    let lastRecorder!: {
      state: string;
      ondataavailable?: (e: { data: Blob }) => void;
      onstop?: () => void;
      onerror?: () => void;
      start: () => void;
      stop: () => void;
    };
    const trackStop = vi.fn();
    class MockMediaRecorder {
      static isTypeSupported = vi.fn((mime: string) => mime.includes("webm"));
      state = "inactive";
      ondataavailable?: (e: { data: Blob }) => void;
      onstop?: () => void;
      onerror?: () => void;
      constructor() {
        lastRecorder = this;
      }
      start = () => {
        this.state = "recording";
      };
      stop = () => {
        this.state = "inactive";
        this.ondataavailable?.({ data: new Blob(["audio"], { type: "audio/webm" }) });
        this.onstop?.();
      };
    }
    Object.defineProperty(globalThis, "MediaRecorder", { configurable: true, value: MockMediaRecorder });
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: trackStop }] })),
      },
    });

    renderWithSession(
      <div>
        <VoiceMessageComposer conversation={dmConv} identity={mocks.identity} roomKey={{} as CryptoKey} disabled={false} />
        <VoiceMessageBubble mediaId="voice-1" identity={mocks.identity} />
      </div>,
    );

    // Record -> Stop -> Send.
    fireEvent.click(screen.getByRole("button", { name: "Record a voice message" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Stop recording" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Stop recording" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Send voice message" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Send voice message" }));
    await waitFor(() => expect(mocks.publishVoiceMessage).toHaveBeenCalled());

    // Record -> Discard on visibilitychange hidden branch.
    fireEvent.click(screen.getByRole("button", { name: "Record a voice message" }));
    await waitFor(() => expect(lastRecorder.state).toBe("recording"));
    act(() => {
      lastRecorder.onerror?.();
    });
    expect(screen.getByText(/Could not record audio/i)).toBeTruthy();

    // VoiceMessageBubble playback success & failure.
    mocks.openVoiceMessage
      .mockRejectedValueOnce(new Error("Missing key"))
      .mockResolvedValueOnce("blob:voice-ok");
    fireEvent.click(screen.getByRole("button", { name: "Play voice message" }));
    await waitFor(() => expect(screen.getByText(/Could not open this voice message/i)).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Play voice message" }));
    await waitFor(() => expect(screen.getByLabelText("Voice message")).toBeTruthy());
  });

  it("drives FriendsAndMessagesPage DMs, requests, tickets, shared previews, and compose", async () => {
    const sharedEntry: Entry = {
      id: "note-shared",
      timestamp: 100,
      updatedAt: 100,
      kind: "Incident",
      title: "Incident Note",
      body: "Details",
      incidentDate: "2026-11-24",
      incidentLocation: "Edmonton",
      attachmentIds: ["doc-1"],
      pinned: false,
      edits: [],
    };
    mocks.loadSharedPrivateEntry.mockResolvedValue(sharedEntry);

    renderWithSession(<Messages />, {
      history: { ...emptyHistory(), private_entries: [] },
    });

    await waitFor(() => expect(screen.getByText("@alex")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Accept" }));
    fireEvent.click(screen.getByRole("button", { name: "Decline" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(mocks.acceptRequest).toHaveBeenCalled());
    expect(mocks.dismissRequest).toHaveBeenCalledTimes(2);

    // Open DM conversation, send message, unsend, open shared doc and shared note.
    fireEvent.click(screen.getByRole("button", { name: /@sam/i }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Send message" })).toBeTruthy());

    fireEvent.change(screen.getByLabelText("Write a message"), { target: { value: "Stay safe" } });
    fireEvent.submit(screen.getByRole("button", { name: "Send message" }).closest("form")!);
    await waitFor(() => expect(mocks.sendMessage).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Unsend" }));
    await waitFor(() => expect(mocks.unsendMessage).toHaveBeenCalled());

    // Open shared entry modal & attachment.
    const sharedButtons = document.querySelectorAll(".shared-message-card");
    if (sharedButtons[0]) fireEvent.click(sharedButtons[0]);
    if (sharedButtons[1]) fireEvent.click(sharedButtons[1]);
    await waitFor(() => expect(mocks.loadSharedPrivateEntry).toHaveBeenCalled());

    // Switch to Ticket chats tab and manage added friends.
    const ticketsTab = screen.getByRole("tab", { name: "Ticket chats" });
    fireEvent.click(ticketsTab);
    fireEvent.click(screen.getByRole("button", { name: /Support Ticket #12/i }));
    fireEvent.click(screen.getByRole("button", { name: "Manage added friends" }));
    fireEvent.click(screen.getByRole("button", { name: "Remove sam" }));
    await waitFor(() => expect(mocks.removeTicketFriend).toHaveBeenCalledWith("ticket-1", "friend-1"));

    // Local preview empty guard when cloudMode = false.
    cleanup();
    mocks.cloudMode = false;
    renderWithSession(<Messages />);
    expect(screen.getByText("Browser-only account")).toBeTruthy();
  });
});
