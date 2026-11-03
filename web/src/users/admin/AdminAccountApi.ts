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
}

// Web admin: Calls the matching backend service admin. It sends a signed-in request to the protected cloud function and checks the reply.
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
  const result = (await callAdmin("adminListAccounts")) as { accounts: ManagedAccount[] };
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
