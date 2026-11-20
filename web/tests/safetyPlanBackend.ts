import { readSafetyChecklist, writeSafetyChecklist } from "../src/services/SafetyChecklistCodec";

const viewer = new URLSearchParams(location.search).get("role");
export const cloudMode = true;
export const accounts = {
  identity: viewer === "admin" ? { uid: "admin", role: "ADMIN", username: "Admin fixture" }
    : viewer === "staff" ? { uid: "staff", role: "STAFF", username: "Staff fixture" }
    : viewer === "contact" ? { uid: "trusted", role: "SAFE_CONTACT", username: "Trusted" }
    : { uid: "owner", role: "USER", username: "Owner" },
};
export const friend = { friendUid: "trusted", friendUsername: "Trusted", friendshipId: "friendship" };

const form = (text: string) => ({ value: writeSafetyChecklist([
  { id: "id-step", text, checked: false },
  { id: "other-step", text: "Unaffected step", checked: true },
]), revision: 1 });
function mockPlan(ownerUid: string, ownerName: string, text: string) {
  return {
    ownerUid, ownerName,
    fields: { primary_documents: form(text), backup_documents: form("Backup identification") },
    staffUid: "", staffName: "", consultationRequested: false, safeContactUids: [], safeContactNames: {},
  };
}
const plans = {
  owner: mockPlan("owner", "Owner", "Prepare identification"),
  other: mockPlan("other", "Other owner", "Other identification"),
};
if (viewer === "staff") {
  Object.assign(plans.owner, { staffUid: "staff", consultationConversationId: "consult-room", ticketNumber: 500 });
  const items = readSafetyChecklist(plans.owner.fields.primary_documents.value);
  items[0].attachmentIds = ["doc", "missing"];
  plans.owner.fields.primary_documents.value = writeSafetyChecklist(items);
}
const watchers = new Map();
export async function loadPlan(uid: keyof typeof plans) { return structuredClone(plans[uid]); }
export async function observePlan(uid: string, update: (plan: unknown) => void, fail: (error: Error) => void) {
  watchers.set(uid, { update, fail });
  return () => watchers.delete(uid);
}
export async function observeSafetyAccess(_uid: string, update: () => void) {
  window.addEventListener("plan-access", update);
  return () => window.removeEventListener("plan-access", update);
}
let accessRevoked = false;
let nextEditConflicts = false;
Object.assign(window, { planMock: {
  conflictNextEdit() { nextEditConflicts = true; },
  revokeAccess(owner: string) {
    accessRevoked = true;
    watchers.get(owner)?.fail(new Error("Safety plan access removed"));
    window.dispatchEvent(new Event("plan-access"));
  },
  pushRemoteChecklist() {
    plans.owner.fields.primary_documents = form("New staff identification");
    watchers.get("owner")?.update(structuredClone(plans.owner));
  },
} });
export async function sharedPlans() {
  return accessRevoked ? [] : Object.values(plans).map(({ ownerUid, ownerName }) => ({ ownerUid, ownerName, updatedAt: 1 }));
}
export async function saveField(_plan: unknown, key: string, value: string) {
  if (nextEditConflicts) {
    nextEditConflicts = false;
    plans.owner.fields.primary_documents = form("Incoming identification");
    return { plan: structuredClone(plans.owner), conflicts: [key], conflictValues: { [key]: value } };
  }
  const fields = plans.owner.fields as Record<string, { value: string; revision: number }>;
  fields[key] = { value, revision: (fields[key]?.revision ?? 0) + 1 };
  return { plan: structuredClone(plans.owner), conflicts: [] };
}
export async function resolvePlanConflict() { throw new Error("No remote conflict is pending"); }
export async function planMediaRecipients() {
  return [{ uid: friend.friendUid, username: friend.friendUsername }];
}
export async function sharePlanWithContacts(friends: typeof friend[]) {
  Object.assign(plans.owner, { safeContactUids: friends.map(row => row.friendUid) });
  return structuredClone(plans.owner);
}
let available = true;
let completed = false;
export async function staffConsultations() {
  return { available, plans: completed ? [] : [
    { ownerUid: "owner", ownerName: "Owner", updatedAt: 1, ticketNumber: 500 },
  ] };
}
export async function setStaffAvailability(next: boolean) {
  available = next;
  return { available };
}
export async function completePlans(conversationId: string) {
  completed = true;
  if (conversationId !== "consult-room") throw new Error("Wrong consultation");
}
export async function consultation(action: "request" | "stop") {
  Object.assign(plans.owner, {
    consultationRequested: action === "request",
    consultationConversationId: action === "request" ? "consult-room" : undefined,
    staffUid: action === "request" ? "staff" : "",
  });
  return structuredClone(plans.owner);
}

const documents = [
  { id: "doc", name: "Identification.txt", ownerUid: "owner", approved: true, mimeType: "text/plain", ciphertextSize: 30, evidence: false },
  { id: "locked", name: "Other device.txt", ownerUid: "other", approved: false, mimeType: "text/plain", ciphertextSize: 30, evidence: true, approvalMessage: "Device approval is required." },
];
const listeners = new Set<(files: typeof documents) => void>();
export async function observeBrowserDocuments(_identity: unknown, update: (files: typeof documents) => void) {
  listeners.add(update);
  update([...documents]);
  return () => listeners.delete(update);
}
export async function shareBrowserPlanDocument(id: string) {
  if (!documents.some(file => file.id === id)) throw new Error("Unknown document");
}
export async function observeDocumentRecipientDevices() { return () => {}; }
export async function openBrowserDocument() {
  return URL.createObjectURL(new Blob(["Private sample document"], { type: "text/plain" }));
}
export async function uploadBrowserDocument(file: File) {
  if (file.size === 0) throw new Error("This file is empty.");
  documents.push({ id: "uploaded", name: file.name, ownerUid: "owner", approved: true, mimeType: file.type, ciphertextSize: file.size, evidence: false });
  for (const update of listeners) update([...documents]);
  return "uploaded";
}
export async function listBrowserDocuments() { return [...documents]; }
export async function setBrowserDocumentEvidence(id: string, evidence: boolean) {
  const file = documents.find(document => document.id === id);
  if (!file) throw new Error("Document missing");
  file.evidence = evidence;
}
export async function firebase() { throw new Error("Unexpected call to Firebase"); }
export { readSafetyChecklist, writeSafetyChecklist };
export { olderSafetySections, safetyChecklistConflictPreview, safetyChecklistText, safetySections, safetySectionLabels } from "../src/services/SafetyChecklistCodec";
