import type { ManagedAccount } from "../src/users/admin/AdminAccountApi";

const rows: ManagedAccount[] = [
  { uid: "pending", username: "pending_person", role: "USER", fullName: "", email: "", phone: "", profileComplete: false, protected: false },
  { uid: "main", username: "main_admin", role: "ADMIN", fullName: "Test Administrator", email: "", phone: "", profileComplete: true, protected: true },
];
export async function listAccounts() { return structuredClone(rows); }
export async function createAccount(form: Record<string, FormDataEntryValue>) {
  const username = String(form.username);
  rows.push({
    uid: username, username, role: form.role as ManagedAccount["role"], fullName: String(form.fullName),
    email: "", phone: "", profileComplete: false, protected: false,
  });
}
export async function deleteAccount(uid: string) {
  const index = rows.findIndex(row => row.uid === uid && !row.protected);
  if (index < 0) throw new Error("Account unavailable for deletion");
  rows.splice(index, 1);
}
