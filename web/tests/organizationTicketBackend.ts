import type { ChatMembershipEvent, Conversation, Message } from "../src/services/MessagingService";

const tickets: Conversation[] = [
  ticket("active", "Support Ticket #501"),
  { ...ticket("archived", "Support Ticket #499"), closedAt: 100 },
  { ...ticket("consult", "Consult Ticket #500"), supportOrigin: "PLAN_CONSULT" },
];
function ticket(id: string, displayName: string): Conversation {
  return {
    id, friendshipId: "", participantIds: ["owner", "staff"], participantUsernames: { owner: "Hidden account", staff: "Staff" },
    lastMessage: displayName, updatedAt: 1, type: "SUPPORT", ownerUid: "owner", staffUid: "staff", adminUid: "",
    supportOrigin: "SUPPORT", supportTag: "SUPPORT", closedAt: 0,
  };
}
const inboxListeners = new Set<(rows: Conversation[]) => void>();
const messageListeners = new Map<string, (rows: Message[]) => void>();
const eventListeners = new Map<string, (rows: ChatMembershipEvent[]) => void>();
const messages = new Map<string, Message[]>([["active", [
  { id: "start", senderUid: "owner", senderUsername: "Hidden account", body: "Could you help with this?", deleted: false, clientSentAt: 1 },
  { id: "unsent", senderUid: "owner", senderUsername: "Hidden account", body: "Removed private words", deleted: true, clientSentAt: 2 },
]]]);
const events = new Map<string, ChatMembershipEvent[]>();
const broadcastTickets = () => inboxListeners.forEach(update => update(structuredClone(tickets)));
export async function register() {}
export async function prepareSupportTickets() {}
export async function subscribeConversations(_uid: string, update: (rows: Conversation[]) => void) {
  inboxListeners.add(update);
  update(structuredClone(tickets));
  return () => inboxListeners.delete(update);
}
export async function roomKey() { return crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt"]); }
export async function subscribeMessages(id: string, _key: CryptoKey, update: (rows: Message[]) => void) {
  messageListeners.set(id, update);
  update(messages.get(id) ?? []);
  return () => messageListeners.delete(id);
}
export async function subscribeChatDeviceKey(_id: string, _uid: string, update: (available: boolean) => void) {
  update(true);
  return () => {};
}
export async function subscribeMembershipEvents(id: string, update: (rows: ChatMembershipEvent[]) => void) {
  eventListeners.set(id, update);
  update(events.get(id) ?? []);
  return () => eventListeners.delete(id);
}
export function conversationName(item: Conversation) { return item.lastMessage; }
export function chatSenderName(item: Conversation, message: Message) {
  return message.senderUid === item.ownerUid ? "Ticket participant" : message.senderUsername;
}
export function membershipEventText(_item: Conversation, event: ChatMembershipEvent) {
  return event.action === "ADMIN_HELP_REQUESTED" ? "Admin help requested" : "Membership updated";
}
export async function sendMessage(id: string, identity: {uid: string; username: string}, body: string) {
  const rows = [...messages.get(id) ?? [], {
    id: "reply", senderUid: identity.uid, senderUsername: identity.username,
    body, deleted: false, clientSentAt: 3,
  }];
  messages.set(id, rows);
  messageListeners.get(id)?.(rows);
}
export async function requestAdministratorHelp(id: string) {
  const conversation = tickets.find(row => row.id === id);
  if (!conversation) throw new Error("Unknown ticket");
  conversation.supportTag = "NEEDS_ADMIN";
  const rows: ChatMembershipEvent[] = [{
    id: "escalated", action: "ADMIN_HELP_REQUESTED", actorUid: "staff", actorName: "Staff",
    participantUid: "", participantName: "", createdAt: 4,
  }];
  events.set(id, rows);
  broadcastTickets();
  eventListeners.get(id)?.(rows);
}
export async function closeSupportTicket(id: string) {
  const conversation = tickets.find(row => row.id === id);
  if (!conversation) throw new Error("Unknown ticket");
  conversation.closedAt = 5;
  broadcastTickets();
}
