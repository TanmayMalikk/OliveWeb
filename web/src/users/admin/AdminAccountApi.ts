// Admins: calls account-management endpoints for admins.
import { cloudMode, firebase } from "../../services/AccountService";
import type { Role } from "../../domain/DomainModels";

export interface ManagedAccount {
  uid: string;
  username: string;
  role: Role;
  fullName: string;
  email: string;
  phone: string;
  profileComplete: boolean;
  protected: boolean;
  assignedUsers?: number;
  safetyPlans?: number;
  completedSafetyPlans?: number;
  supportChats?: number;
  closedSupportChats?: number;
}

export interface AdminDashboardSummary {
  counts: {
    USER: number;
    SAFE_CONTACT: number;
    STAFF: number;
    ADMIN: number;
  };
  tickets: {
    open: number;
    closed: number;
  };
}

// Sends a signed-in admin request to the protected Cloud Function.
async function callAdmin(name: string, input: unknown = {}) {
  if (!cloudMode) {
    throw new Error("Organization management requires a Firebase-connected account.");
  }
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(input)).data;
}

// Lists accounts for web admin.
export async function listAccounts(): Promise<ManagedAccount[]> {
  const result = (await callAdmin("adminListAccounts", { view: "ACCOUNTS" })) as {
    accounts: ManagedAccount[];
  };
  return result.accounts;
}

// Loads account and ticket totals for web admin overview.
export async function loadAdminSummary(): Promise<AdminDashboardSummary> {
  const result = (await callAdmin("adminListAccounts", { view: "SUMMARY" })) as AdminDashboardSummary;
  return result;
}

// Loads staff workload counts for web admin staff management.
export async function listStaffWorkloads(): Promise<ManagedAccount[]> {
  const result = (await callAdmin("adminListAccounts", { view: "STAFF" })) as {
    accounts: ManagedAccount[];
  };
  return result.accounts;
}

// Creates account.
export async function createAccount(input: Record<string, FormDataEntryValue>) {
  await callAdmin("adminCreateAccount", input);
}

// Deletes account.
export async function deleteAccount(uid: string) {
  await callAdmin("adminDeleteAccount", { uid });
}
