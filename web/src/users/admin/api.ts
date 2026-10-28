// Contains the API code for this part of Olive.
import { cloudMode, firebase } from "../../services/accounts";
import type { Role } from "../../domain";

// Groups the data and actions for managed account.
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

async function callAdmin(name: string, input: unknown = {}) {
  if (!cloudMode) {
    throw new Error("Organization management requires a Firebase-connected account.");
  }
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(input)).data;
}

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
