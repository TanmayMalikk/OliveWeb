import { httpsCallable } from "firebase/functions";
import { doc, onSnapshot } from "firebase/firestore";
import { accounts, firebase } from "./AccountService";

export interface FriendInvite { code: string; link: string; expiresAt: number }

async function callProtected(name: string, payload: Record<string, unknown>) {
  const { auth, functions } = await firebase();
  try {
    return (await httpsCallable<Record<string, unknown>, unknown>(functions, name)(payload))?.data;
  } catch (error) {
    const code = (error as { code?: unknown })?.code;
    if (code === "functions/unauthenticated" && auth?.currentUser) {
      throw new Error(
        "App Check verification failed (401). Confirm Firebase App Check is enabled for this domain and reload.",
      );
    }
    throw error;
  }
}

export async function subscribeFriendInvite(uid: string, update: (value: { code: string; claimedBy: string }) => void) {
  const { db } = await firebase();
  return onSnapshot(doc(db, "friendInviteOwners", uid), snapshot => {
    update({ code: String(snapshot.get("code") ?? ""), claimedBy: String(snapshot.get("claimedBy") ?? "") });
  }, () => undefined);
}
export async function createFriendInvite(refresh = false): Promise<FriendInvite> {
  const identity = accounts.identity;
  if (!identity) throw new Error("Sign in first.");
  const result = (await callProtected("friendInviteCreate", { refresh })) as FriendInvite;
  if (accounts.identity?.uid !== identity.uid) throw new Error("Your account changed. Open Invite again.");
  return result;
}
export async function claimFriendInvite(code: string): Promise<{ friendshipId: string; username: string }> {
  const identity = accounts.identity;
  if (!identity) throw new Error("Sign in first.");
  const { register } = await import("./MessagingService");
  await register(identity);
  const result = (await callProtected("friendInviteClaim", { code: code.trim() })) as {
    friendshipId: string;
    username: string;
  };
  if (accounts.identity?.uid !== identity.uid) throw new Error("Your account changed. Reopen Messages.");
  return result;
}
export async function authorizeMessagingPeer(username: string): Promise<void> {
  await callProtected("messagingAuthorizePeer", { username: username.trim().toLowerCase() });
}
