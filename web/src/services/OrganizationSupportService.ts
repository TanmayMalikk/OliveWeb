// Staff and admins: performs support-ticket actions through trusted Firebase functions.
import { cloudMode, firebase } from "./AccountService";

// Calls a role-checked function so the browser cannot grant itself staff access.
async function callSupport<T>(name: string, input: unknown) {
  if (!cloudMode)
    throw new Error("Support inbox requires a shared Firebase account.");
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(input)).data as T;
}

// Staff: adds an administrator to this existing support conversation.
export async function requestAdministratorHelp(conversationId: string) {
  return callSupport<{ conversationId: string; adminUid: string }>(
    "safetySupportAdmin",
    { conversationId },
  );
}

// Staff: closes the active ticket and archives its conversation for every participant.
export async function closeSupportTicket(conversationId: string) {
  return callSupport<{ conversationId: string }>("staffSupportClose", {
    conversationId,
  });
}
