import type { ManagedAccount } from "../src/users/admin/AdminAccountApi";

const rows: ManagedAccount[] = [
  { uid: "pending", username: "pending_person", role: "USER", fullName: "", email: "", phone: "", profileComplete: false, protected: false },
  { uid: "main", username: "main_admin", role: "ADMIN", fullName: "Test Administrator", email: "", phone: "", profileComplete: true, protected: true },
];
export async function listAccounts() { return structuredClone(rows); }
export async function loadAdminSummary() {
  return {
    counts: {
      USER: rows.filter((row) => row.role === "USER").length,
      SAFE_CONTACT: rows.filter((row) => row.role === "SAFE_CONTACT").length,
      STAFF: rows.filter((row) => row.role === "STAFF").length,
      ADMIN: rows.filter((row) => row.role === "ADMIN").length,
    },
    tickets: { open: 1, closed: 0 },
  };
}
export async function listStaffWorkloads() {
  return structuredClone(rows)
    .filter((row) => row.role === "STAFF")
    .map((row) => ({
      ...row,
      assignedUsers: 0,
      safetyPlans: 0,
      completedSafetyPlans: 0,
      supportChats: 0,
      closedSupportChats: 0,
    }));
}
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
