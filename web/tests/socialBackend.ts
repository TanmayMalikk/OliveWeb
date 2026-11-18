import type { DirectoryUser, Friend, Request } from "../src/services/MessagingService";
import { friend } from "./safetyPlanBackend";

const identity = { uid: "owner", username: "Owner" };
const candidate: DirectoryUser = { uid: "candidate", username: "new_contact", publicKey: "fixture-key" };
const known: DirectoryUser = { uid: friend.friendUid, username: friend.friendUsername, publicKey: "fixture-key" };
const requests = (sender: { uid: string; username: string }, recipient: { uid: string; username: string }): Request => ({
  id: sender.uid + ":" + recipient.uid,
  senderUid: sender.uid, senderUsername: sender.username,
  recipientUid: recipient.uid, recipientUsername: recipient.username,
  friendshipId: "friendship-" + sender.uid,
});
const asking = { uid: "asking", username: "request_contact" };
const ignoring = { uid: "ignoring", username: "decline_contact" };
const state = { friends: [friend] as Friend[], incoming: [requests(asking, identity), requests(ignoring, identity)], outgoing: [] as Request[] };
const observers = new Set<(social: typeof state) => void>();
const broadcast = () => observers.forEach(update => update(structuredClone(state)));
export async function subscribeSocial(_uid: string, update: (social: typeof state) => void) {
  observers.add(update);
  update(structuredClone(state));
  return () => observers.delete(update);
}
export async function searchUser(username: string) {
  return [candidate, known, { ...asking, publicKey: "fixture-key" }].find(row => row.username.toLowerCase() === username.trim().toLowerCase()) ?? null;
}
export async function sendRequest(sender: typeof identity, recipient: DirectoryUser) {
  state.outgoing.push(requests(sender, recipient));
  broadcast();
}
export async function dismissRequest(request: Request) {
  state.incoming = state.incoming.filter(row => row.id !== request.id);
  state.outgoing = state.outgoing.filter(row => row.id !== request.id);
  broadcast();
}
export async function acceptRequest(_identity: typeof identity, request: Request) {
  state.friends.push({ friendUid: request.senderUid, friendUsername: request.senderUsername, friendshipId: request.friendshipId });
  await dismissRequest(request);
}
