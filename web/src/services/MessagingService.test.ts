import { beforeEach, expect, it, vi } from "vitest";
import * as chat from "./MessagingService";
import type { Identity } from "../domain/DomainModels";

const f = vi.hoisted(() => ({
  records: new Map<string, any>(), devices: new Map<string, any>(),
  listeners: [] as {path: string, update: (...args: any[]) => any, error: (...args: any[]) => any, stop: ReturnType<typeof vi.fn>}[],
  writes: [] as any[], call: vi.fn(), failCommit: false,
}));
vi.mock("./AccountService", () => ({firebase: async () => ({db: {}, functions: {}})}));
vi.mock("./LocalDataStore", () => ({database: Promise.resolve({
  get: async (_: string, key: string) => f.devices.get(key),
  put: async (_: string, value: any, key: string) => f.devices.set(key, value),
})}));
vi.mock("firebase/functions", () => ({httpsCallable: (_: unknown, name: string) => (data: unknown) => f.call(name, data)}));
vi.mock("firebase/firestore", () => {
  const reference = (base: any, ...parts: string[]) => ({path: [base?.path, ...parts].filter(Boolean).join("/") || "auto", id: parts.at(-1) ?? "auto"});
  const snapshot = (ref: any) => ({exists: () => f.records.has(ref.path), data: () => f.records.get(ref.path), get: (key: string) => f.records.get(ref.path)?.[key]});
  const batch = () => ({
    set: (ref: any, value: any) => f.writes.push(["set", ref.path, value]),
    update: (ref: any, value: any) => f.writes.push(["update", ref.path, value]),
    delete: (ref: any) => f.writes.push(["delete", ref.path]),
    commit: async () => {if (f.failCommit) throw new Error("offline");},
  });
  return {
    collection: reference, doc: reference, getDoc: async (ref: any) => snapshot(ref),
    getDocs: async () => ({docs: [], empty: true}),
    query: (ref: any) => ref, where: vi.fn(), orderBy: vi.fn(), limitToLast: vi.fn(), limit: vi.fn(),
    serverTimestamp: () => "server-time", writeBatch: batch,
    setDoc: async (ref: any, value: any) => {f.writes.push(["set", ref.path, value]); f.records.set(ref.path, {...f.records.get(ref.path), ...value});},
    deleteDoc: async (ref: any) => {f.writes.push(["delete", ref.path]); f.records.delete(ref.path);},
    onSnapshot: (ref: any, ...args: any[]) => {
      const stop = vi.fn(); const callbacks = args.filter(value => typeof value === "function");
      f.listeners.push({path: ref.path, update: callbacks[0], error: callbacks[1], stop}); return stop;
    },
    runTransaction: async (_: unknown, action: any) => action({...batch(), get: async (ref: any) => snapshot(ref)}),
  };
});
const current: Identity = {uid: "owner", username: "OwnerName", role: "USER"};
const conversation = (changes: Partial<chat.Conversation> = {}): chat.Conversation => ({
  id: "chat", friendshipId: "friendship", participantIds: ["owner", "friend"], participantUsernames: {owner: "ownername", friend: "buddy"},
  lastMessage: "", updatedAt: 0, type: "DIRECT", ownerUid: "owner", staffUid: "staff", adminUid: "admin", supportOrigin: "", supportTag: "", closedAt: 0, ...changes,
});
const event = (action: chat.ChatMembershipEvent["action"]): chat.ChatMembershipEvent => ({id: "event", action, actorUid: "owner", actorName: "Alice", participantUid: "friend", participantName: "Bob", createdAt: 1});
const row = (id: string, data: any) => ({id, data: () => data});
beforeEach(() => {f.records.clear(); f.devices.clear(); f.listeners.length = 0; f.writes.length = 0; f.call.mockReset(); f.failCommit = false;});

it("anonymizes ticket senders and membership logs for staff without hiding names from users", () => {
  const ticket = conversation({type: "SUPPORT", ticketNumber: 524});
  for (const role of ["STAFF", "ADMIN"] as const) {
    expect(chat.chatSenderName(ticket, {senderUid: "owner", senderUsername: "Alice"}, role)).toBe("Ticket #524");
    expect(chat.chatSenderName(ticket, {senderUid: "friend", senderUsername: "Bob"}, role)).toBe("Friend");
    expect(chat.membershipEventText(ticket, event("ADDED"), role)).toBe("Ticket #524 added a friend");
    expect(chat.membershipEventText(ticket, event("REMOVED"), role)).toBe("Ticket #524 removed a friend");
  }
  expect(chat.chatSenderName(ticket, {senderUid: "admin", senderUsername: "private admin"}, "USER")).toBe("Olive Admin");
  expect(chat.chatSenderName(ticket, {senderUid: "staff", senderUsername: "private staff"}, "USER")).toBe("Olive Staff");
  expect(chat.chatSenderName(ticket, {senderUid: "friend", senderUsername: "Bob"}, "USER")).toBe("Bob");
  expect(chat.membershipEventText(ticket, event("ADDED"), "USER")).toBe("Alice added Bob");
  expect(chat.membershipEventText(ticket, event("ADMIN_HELP_REQUESTED"), "USER")).toBe("Admin help requested");
  expect(chat.membershipEventText(conversation(), {...event("REMOVED"), actorName: "", participantName: ""}, "USER")).toBe("Chat member removed Chat member");
  expect(chat.chatSenderName({...ticket, ticketNumber: undefined}, {senderUid: "owner", senderUsername: "Alice"}, "ADMIN")).toBe("Ticket");
});
it("uses stable pair IDs and matching ticket titles", () => {
  expect(chat.pairId("b", "a")).toBe(chat.pairId("a", "b"));
  expect(chat.conversationName(conversation(), current)).toBe("buddy");
  expect(chat.conversationName(conversation({participantUsernames: {owner: "me"}}), current)).toBe("Conversation");
  for (const [changes, title] of [
    [{}, "Support Ticket"], [{supportOrigin: "PLAN_CONSULT"}, "Consult Ticket"], [{supportTag: "PLAN_CONSULT"}, "Consult Ticket"], [{supportTag: "NEEDS_ADMIN"}, "Admin Ticket"],
  ] as const) expect(chat.conversationName(conversation({type: "SUPPORT", ...changes, ticketNumber: 524}), current)).toBe(`${title} #524`);
});
it("searches exact normalized usernames and never returns the current account", async () => {
  f.records.set("messageDirectory/buddy", {uid: "friend", username: "buddy", publicKey: "public"});
  expect((await chat.searchUser(" Buddy ", "owner"))?.uid).toBe("friend");
  expect(await chat.searchUser("buddy", "friend")).toBeNull();
  expect(await chat.searchUser("absent", "owner")).toBeNull();
  await expect(chat.searchUser("bad!", "owner")).rejects.toThrow("exact");
});
it("registers a stable browser key per account and refuses another account's directory entry", async () => {
  const a = await chat.messageDevice("owner");
  expect(await chat.messageDevice("owner")).toEqual(a);
  expect(await chat.messageDevice("other")).not.toEqual(a);
  expect(a.deviceId).toMatch(/^[A-Za-z0-9_-]+$/);
  await chat.register(current);
  expect(f.records.get("messageDirectory/ownername").uid).toBe("owner");
  expect(f.writes.at(-1)[2]).toMatchObject({platform: "WEB", mgf1Digest: "SHA-256"});
  f.records.set("messageDirectory/ownername", {uid: "intruder"});
  await expect(chat.register(current)).rejects.toThrow("belongs to another account");
});
it("writes one consistent friendship batch and rejects accepting someone else's request", async () => {
  const request: chat.Request = {id: "req", senderUid: "friend", senderUsername: "buddy", recipientUid: "owner", recipientUsername: "ownername", friendshipId: "friend--owner"};
  await expect(chat.acceptRequest({...current, uid: "stranger"}, request)).rejects.toThrow("Only the recipient");
  await chat.acceptRequest(current, request);
  expect(f.writes.filter(w => w[1].startsWith("friendLists/"))).toHaveLength(2);
  expect(f.writes.find(w => w[1] === "conversations/friend--owner")[2]).toMatchObject({participantIds: ["friend", "owner"], lastMessage: ""});
  expect(f.writes.at(-1)).toEqual(["delete", "friendRequests/req"]);
  await chat.dismissRequest(request);
  await chat.sendRequest(current, {uid: "friend", username: "buddy", publicKey: "key"});
  expect(f.writes.at(-1)[2]).toMatchObject({senderUid: "owner", recipientUid: "friend", friendshipId: "friend--owner"});
});
it("subscribes to all social lists, forwards errors, and closes every listener", async () => {
  const updated = vi.fn(), error = vi.fn();
  const close = await chat.subscribeSocial("owner", updated, error);
  for (const listener of f.listeners) listener.update({docs: [row("one", {friendUid: "buddy"})]});
  expect(updated.mock.calls.at(-1)![0]).toMatchObject({friends: [{friendUid: "buddy"}], incoming: [{id: "one"}], outgoing: [{id: "one"}]});
  f.listeners[0].error(new Error("denied")); expect(error).toHaveBeenCalled();
  close(); for (const listener of f.listeners) expect(listener.stop).toHaveBeenCalledOnce();
});
it("ignores cached ticket snapshots and sorts server conversations without inventing ticket IDs", async () => {
  const update = vi.fn(); await chat.subscribeConversations("owner", update, vi.fn());
  const listener = f.listeners[0];
  listener.update({metadata: {fromCache: true}, docs: []}); expect(update).not.toHaveBeenCalled();
  listener.update({metadata: {fromCache: false}, docs: [row("old", {}), row("new", {type: "SUPPORT", updatedAt: {toMillis: () => 20}, ticketNumber: 524}), row("invalid", {ticketNumber: -1})]});
  expect(update.mock.calls[0][0].map((c: chat.Conversation) => c.id)).toEqual(["new", "old", "invalid"]);
  expect(update.mock.calls[0][0][2].ticketNumber).toBeUndefined();
});
it("filters invalid membership events and cached events and clears on permission loss", async () => {
  const update = vi.fn(); await chat.subscribeMembershipEvents("chat", update, "owner");
  const listener = f.listeners[0];
  listener.update({metadata: {fromCache: true}, docs: []}); expect(update).not.toHaveBeenCalled();
  listener.update({metadata: {fromCache: false}, docs: [row("valid", {...event("ADDED"), createdAt: {toMillis: () => 100}}), row("bad", {...event("ADDED"), action: "UNKNOWN"})]});
  expect(update.mock.calls[0][0]).toHaveLength(1); expect(update.mock.calls[0][0][0].createdAt).toBe(100);
  listener.error(new Error("denied")); expect(update).toHaveBeenLastCalledWith([]);
});
it("encrypts randomized messages and safely renders deleted, corrupt and valid ciphertext", async () => {
  const key = await crypto.subtle.generateKey({name: "AES-GCM", length: 256}, false, ["encrypt", "decrypt"]);
  const ciphertext = await chat.encryptChatMessage("Private test message", key);
  expect(await chat.encryptChatMessage("Private test message", key)).not.toBe(ciphertext);
  const updated = vi.fn(); await chat.subscribeMessages("chat", key, updated, vi.fn());
  await f.listeners[0].update({docs: [row("valid", {ciphertext, deleted: false}), row("deleted", {ciphertext, deleted: true}), row("bad", {ciphertext: "bad", deleted: false}), row("empty", {deleted: false})]});
  expect(updated.mock.calls[0][0].map((m: chat.Message) => m.body)).toEqual(["Private test message", "", "Unable to decrypt this message on this device.", ""]);
  await expect(chat.sendMessage("chat", current, " ", key)).rejects.toThrow("1–2,000");
  await expect(chat.sendMessage("chat", current, "x".repeat(2001), key)).rejects.toThrow("1–2,000");
  await chat.sendMessage("chat", current, " hello\r\nworld ", key);
  const saved = f.writes.find(w => w[1] === "conversations/chat/messages")[2];
  expect(saved.ciphertext).not.toContain("hello"); expect(saved.body).toBeUndefined();
  expect(f.writes.at(-1)[2].lastMessage).toBe("Secure message");
  f.failCommit = true; await expect(chat.sendMessage("chat", current, "retry", key)).rejects.toThrow("offline");
});
it("only unsends an owned, live message and changes the preview only for the latest message", async () => {
  const message = {id: "message", senderUid: "owner", senderUsername: "ownername", body: "hi", deleted: false, clientSentAt: 1};
  await expect(chat.unsendMessage("chat", {...message, senderUid: "other"}, current)).rejects.toThrow("only unsend your own");
  f.records.set("conversations/chat/messages/message", {senderUid: "owner", ciphertext: "encrypted"});
  f.records.set("conversations/chat", {lastMessageId: "message"});
  await chat.unsendMessage("chat", message, current);
  expect(f.writes.at(-2)[2]).toMatchObject({deleted: true, ciphertext: "Message unsent"});
  expect(f.writes.at(-1)[1]).toBe("conversations/chat");
  f.records.set("conversations/chat/messages/message", {senderUid: "other"});
  await expect(chat.unsendMessage("chat", message, current)).rejects.toThrow("cannot be unsent");
  f.records.set("conversations/chat/messages/message", {senderUid: "owner", deleted: true});
  await expect(chat.unsendMessage("chat", message, current)).rejects.toThrow("cannot be unsent");
});
it("uses the authenticated callable for removing a ticket friend and preparing support tickets", async () => {
  await chat.removeTicketFriend("chat", "friend"); await chat.prepareSupportTickets();
  expect(f.call).toHaveBeenCalledWith("conversationRemoveParticipant", {conversationId: "chat", participantUid: "friend"});
  expect(f.call).toHaveBeenCalledWith("supportTicketsPrepare", undefined);
});

it("writes one encrypted room key for a new ticket and opens it again with the same browser", async () => {
  const user = { ...current, username: "ownername" };
  const device = await chat.messageDevice(user.uid);
  const peerDevice = await chat.messageDevice("friend");
  f.records.set("messageDirectory/buddy", {uid: "friend", publicKey: peerDevice.publicKey});
  f.records.set("conversations/chat", {});
  f.call.mockResolvedValue({data: {participantUsernames: {owner: "ownername", friend: "buddy"}}});
  const ticket = conversation({type: "SUPPORT", participantUsernames: {}});
  const key = await chat.roomKey("chat", user, ticket);
  expect(f.call).toHaveBeenCalledWith("conversationPrepareKeys", {conversationId: "chat"});
  const update = f.writes.find(([method, path]) => method === "update" && path === "conversations/chat");
  expect(update?.[2].participantPublicKeys).toEqual({owner: device.publicKey, friend: peerDevice.publicKey});
  expect(Object.keys(update?.[2].roomKeys)).toEqual(["owner", "friend"]);

  const reopened = await chat.roomKey("chat", user, ticket);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({name: "AES-GCM", iv}, key, new TextEncoder().encode("Private test message"));
  const plaintext = await crypto.subtle.decrypt({name: "AES-GCM", iv}, reopened, encrypted);
  expect(new TextDecoder().decode(plaintext)).toBe("Private test message");
  expect(f.writes.filter(([method, path]) => method === "update" && path === "conversations/chat")).toHaveLength(1);
});

it("requires a current participant key and refuses to change keys for an existing history", async () => {
  await expect(chat.roomKey("chat", current, conversation())).rejects.toThrow("Every person in this chat");
  const peerDevice = await chat.messageDevice("friend");
  f.records.set("messageDirectory/buddy", {uid: "friend", publicKey: peerDevice.publicKey});
  await expect(chat.roomKey("chat", current, conversation())).rejects.toThrow("no longer available");
  f.records.set("conversations/chat", {
    participantPublicKeys: {owner: "different-device"}, roomKeys: {owner: "existing-envelope"}, lastMessageId: "historic",
  });
  await expect(chat.roomKey("chat", current, conversation())).rejects.toThrow("existing authorized device");
  expect(f.writes.some(([method, path]) => method === "update" && path === "conversations/chat")).toBe(false);
});

it("reports only server-confirmed device approval and resets availability on errors", async () => {
  const update = vi.fn();
  const stop = await chat.subscribeChatDeviceKey("chat", current.uid, update);
  const device = await chat.messageDevice(current.uid);
  expect(f.listeners[0].path).toBe(`conversations/chat/deviceKeys/${current.uid}--${device.deviceId}`);
  f.listeners[0].update({metadata: {fromCache: true}, exists: () => true});
  expect(update).not.toHaveBeenCalled();
  f.listeners[0].update({metadata: {fromCache: false}, exists: () => true});
  expect(update).toHaveBeenCalledWith(true);
  f.listeners[0].error(new Error("revoked"));
  expect(update).toHaveBeenLastCalledWith(false);
  stop();
  expect(f.listeners[0].stop).toHaveBeenCalledOnce();
});
