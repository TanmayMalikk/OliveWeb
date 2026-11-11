// All safe-app roles: loads and saves shared safety plans.
import { firebase } from "./AccountService";
import { doc, onSnapshot } from "firebase/firestore";
import { writeSafetyChecklist } from "./SafetyChecklistCodec";
import type { Friend } from "./MessagingService";
export {
  readSafetyChecklist,
  safetyChecklistText,
  writeSafetyChecklist,
} from "./SafetyChecklistCodec";
export type { SafetyChecklistItem } from "./SafetyChecklistCodec";

export const safetySections = [
  "destination",
  "travel",
  "contacts",
  "essentials",
  "dependants",
  "aftercare",
  "documents",
  "grab",
] as const;
export type SafetyField = {
  value: string;
  revision: number;
  mutationId?: string;
};
export type SafetyPlan = {
  ownerUid: string;
  ownerName: string;
  fields: Record<string, SafetyField>;
  staffUid: string;
  staffName: string;
  consultationRequested: boolean;
  safeContactUids: string[];
  safeContactNames: Record<string, string>;
  updatedAt?: number;
  consultationConversationId?: string;
  plansCompletedAt?: number;
  pendingConflicts?: Record<string, { value: string; mutationId: string }>;
};

const starterItems: Record<string, string[]> = {
  destination: [
    "Choose a safe place to go.",
    "Write down the address and how to find it.",
    "Check when you can arrive.",
  ],
  travel: [
    "Decide how you will get there.",
    "Confirm who can help with transport, if needed.",
    "Prepare keys, fare or fuel when it is safe to do so.",
  ],
  contacts: [
    "Write a trusted person’s name and safe contact details.",
    "Ask whether you can stay with them and for how long.",
    "Agree on a safe way to check in or ask for help.",
  ],
  aftercare: [
    "Let a trusted person know you arrived, when safe.",
    "Plan essentials for the first few days: shelter, food and medication.",
    "Identify support services you may want to contact.",
  ],
  documents: [
    "Driver’s licence / government-issued photo ID",
    "Birth certificates",
    "Passport / citizenship / immigration documents",
    "Health card",
    "Prescriptions",
    "Medical records, if needed",
    "Social Insurance Number (SIN)",
    "Court orders / protection orders",
    "Marriage / parenting / custody documents, if applicable",
    "Banking / insurance / housing records, if needed",
  ],
  grab: [
    "House and vehicle keys",
    "Phone and charger",
    "Cash and bank cards",
    "Essential medicines",
    "Basic clothes and toiletries",
    "Children’s or pets’ essentials, if applicable",
  ],
};

function initialDefaults() {
  return Object.fromEntries(
    ["primary", "backup"].flatMap((kind) =>
      safetySections.map((section) => {
        const items = (starterItems[section] ?? []).map((text) => ({
          id: crypto.randomUUID(),
          text,
          checked: false,
        }));
        return [`${kind}_${section}`, writeSafetyChecklist(items)];
      }),
    ),
  );
}

// Sends a signed-in safety-plan request to the protected Cloud Function.
async function call<T>(name: string, data: unknown = {}): Promise<T> {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(data)).data as T;
}
// Loads one user's safety plan through the protected cloud function.
export async function loadPlan(ownerUid: string) {
  return (
    await call<{ plan: SafetyPlan }>("safetyPlanSync", {
      ownerUid,
      defaults: initialDefaults(),
      changes: [],
    })
  ).plan;
}

// Safety plan viewers: watches callable-written plan data without granting the browser write access.
export async function observePlan(
  ownerUid: string,
  onUpdate: (plan: SafetyPlan) => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  return onSnapshot(
    doc(db, "safetyPlans", ownerUid),
    { includeMetadataChanges: true },
    (snapshot) => {
      if (!snapshot.exists() || snapshot.metadata.fromCache) return;
      const data = snapshot.data();
      onUpdate({
        ownerUid: String(data.ownerUid ?? ownerUid),
        ownerName: String(data.ownerName ?? ""),
        fields: (data.fields ?? {}) as Record<string, SafetyField>,
        staffUid: String(data.staffUid ?? ""),
        staffName: String(data.staffName ?? ""),
        consultationRequested: data.consultationRequested === true,
        safeContactUids: Array.isArray(data.safeContactUids)
          ? data.safeContactUids.filter((uid: unknown): uid is string => typeof uid === "string")
          : [],
        safeContactNames: (data.safeContactNames ?? {}) as Record<string, string>,
        updatedAt: typeof data.updatedAt === "number" ? data.updatedAt : 0,
        consultationConversationId: data.consultationConversationId,
        plansCompletedAt: data.plansCompletedAt,
        pendingConflicts: data.pendingConflicts,
      });
    },
    (error) => onError(error),
  );
}

export async function observeSafetyAccess(uid: string, onUpdate: () => void, onError: (error: Error) => void) {
  const { db } = await firebase();
  let revision: number | undefined;
  return onSnapshot(doc(db, "syncInvalidations", uid), { includeMetadataChanges: true }, (snapshot) => {
    if (snapshot.metadata.fromCache) return;
    const next = snapshot.get("safetyRevision") ?? 0;
    if (next === revision) return;
    revision = next;
    onUpdate();
  }, onError);
}
// Saves one safety-plan field without overwriting a newer edit.
export async function saveField(plan: SafetyPlan, key: string, value: string, revision?: number) {
  const current = plan.fields[key] ?? { value: "", revision: 0 };
  return (
    await call<{ plan: SafetyPlan; conflicts: string[] }>("safetyPlanSync", {
      ownerUid: plan.ownerUid,
      defaults: {},
      changes: [
        {
          key,
          value,
          revision: revision ?? current.revision,
          mutationId: crypto.randomUUID(),
        },
      ],
    })
  );
}

export async function resolvePlanConflict(plan: SafetyPlan, key: string, useIncoming: boolean) {
  const proposal = plan.pendingConflicts?.[key];
  if (!proposal) throw new Error("This conflict has already been resolved.");
  return (await call<{ plan: SafetyPlan }>("safetyPlanSync", {
    ownerUid: plan.ownerUid,
    resolution: { key, revision: plan.fields[key]?.revision, mutationId: proposal.mutationId, useIncoming },
  })).plan;
}

export function completePlans(conversationId: string) {
  return call("staffSupportClose", { conversationId, planCompleted: true });
}

export async function planMediaRecipients() {
  return (await call<{ recipients: { uid: string; username: string }[] }>("safetyPlanMediaRecipients")).recipients;
}

export async function sharePlanWithContacts(friends: Friend[]) {
  return (await call<{ plan: SafetyPlan }>("safetyPlanShareContacts", {
    contacts: friends.map((friend) => ({
      uid: friend.friendUid, friendshipId: friend.friendshipId, name: friend.friendUsername,
    })),
  })).plan;
}
// Starts or stops a plan consultation for the signed-in user.
export async function consultation(action: "request" | "stop") {
  return (await call<{ plan: SafetyPlan }>("safetyPlanConsult", { action }))
    .plan;
}
// Loads the safety plans that other users have shared with this account.
export async function sharedPlans() {
  return (
    await call<{
      plans: { ownerUid: string; ownerName: string; updatedAt: number }[];
    }>("safetyPlansSharedWithMe")
  ).plans;
}
// Loads the plan consultations assigned to the signed-in staff member.
export async function staffConsultations() {
  return call<{
    available: boolean;
    plans: { ownerUid: string; ownerName: string; updatedAt: number }[];
  }>("safetyPlanConsultations");
}
// Lets the support queue know whether this staff member can take a ticket.
export async function setStaffAvailability(available: boolean) {
  return call<{ available: boolean }>("safetyPlanAvailability", { available });
}
