// All safe-app roles: loads and saves shared safety plans.
import { firebase } from "./AccountService";
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
};

// Calls the matching backend service for web safety plan service. It sends a signed-in request to the protected cloud function and checks the reply.
async function call<T>(name: string, data: unknown = {}): Promise<T> {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(data)).data as T;
}
// Loads plan.
export async function loadPlan(ownerUid: string) {
  const defaults = Object.fromEntries(
    ["primary", "backup"].flatMap((plan) =>
      safetySections.map((section) => [`${plan}_${section}`, ""]),
    ),
  );
  return (
    await call<{ plan: SafetyPlan }>("safetyPlanSync", {
      ownerUid,
      defaults,
      changes: [],
    })
  ).plan;
}
// Web safety plan service: Saves field.
export async function saveField(plan: SafetyPlan, key: string, value: string) {
  const current = plan.fields[key] ?? { value: "", revision: 0 };
  return (
    await call<{ plan: SafetyPlan; conflicts: string[] }>("safetyPlanSync", {
      ownerUid: plan.ownerUid,
      defaults: {},
      changes: [
        {
          key,
          value,
          revision: current.revision,
          mutationId: crypto.randomUUID(),
        },
      ],
    })
  ).plan;
}
// returns the consultation for web safety plan service.
export async function consultation(action: "request" | "stop") {
  return (await call<{ plan: SafetyPlan }>("safetyPlanConsult", { action }))
    .plan;
}
// returns the shared plans for web safety plan service.
export async function sharedPlans() {
  return (
    await call<{
      plans: { ownerUid: string; ownerName: string; updatedAt: number }[];
    }>("safetyPlansSharedWithMe")
  ).plans;
}
// returns the staff consultations for web safety plan service.
export async function staffConsultations() {
  return call<{
    available: boolean;
    plans: { ownerUid: string; ownerName: string; updatedAt: number }[];
  }>("safetyPlanConsultations");
}
// Updates staff availability for web safety plan service.
export async function setStaffAvailability(available: boolean) {
  return call<{ available: boolean }>("safetyPlanAvailability", { available });
}
