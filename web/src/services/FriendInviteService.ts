import { httpsCallable } from "firebase/functions";
import { doc, onSnapshot } from "firebase/firestore";
import { accounts, firebase } from "./AccountService";

export interface FriendInvite { code: string; link: string; expiresAt: number }
export async function subscribeFriendInvite(uid: string, update: (value: { code: string; claimedBy: string }) => void) {
  const { db } = await firebase();
  return onSnapshot(doc(db, "friendInviteOwners", uid), snapshot => {
    update({ code: String(snapshot.get("code") ?? ""), claimedBy: String(snapshot.get("claimedBy") ?? "") });
  }, () => undefined);
}
export async function createFriendInvite(refresh = false): Promise<FriendInvite> {
  const identity = accounts.identity;
  if (!identity) throw new Error("Sign in first.");
  const { functions } = await firebase();
  const result = (await httpsCallable<{ refresh: boolean }, FriendInvite>(functions, "friendInviteCreate")({ refresh })).data;
  if (accounts.identity?.uid !== identity.uid) throw new Error("Your account changed. Open Invite again.");
  return result;
}
export async function claimFriendInvite(code: string): Promise<{ friendshipId: string; username: string }> {
  const identity = accounts.identity;
  if (!identity) throw new Error("Sign in first.");
  const { register } = await import("./MessagingService");
  await register(identity);
  const { functions } = await firebase();
  const result = (await httpsCallable<{ code: string }, { friendshipId: string; username: string }>(
    functions, "friendInviteClaim",
  )({ code: code.trim() })).data;
  if (accounts.identity?.uid !== identity.uid) throw new Error("Your account changed. Reopen Messages.");
  return result;
}
export async function authorizeMessagingPeer(username: string): Promise<void> {
  const { functions } = await firebase();
  await httpsCallable(functions, "messagingAuthorizePeer")({ username: username.trim().toLowerCase() });
}
