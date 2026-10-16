import {
  collection,
  doc,
  getDoc,
  setDoc,
  deleteDoc,
  onSnapshot,
  query,
  where,
  orderBy,
  limitToLast,
  writeBatch,
  serverTimestamp,
  runTransaction,
} from "firebase/firestore";
import { firebase } from "./accounts";
import type { Identity } from "../domain";
export interface DirectoryUser {
  uid: string;
  username: string;
}
export interface Friend {
  friendshipId: string;
  friendUid: string;
  friendUsername: string;
}
export interface Request {
  id: string;
  senderUid: string;
  senderUsername: string;
  recipientUid: string;
  recipientUsername: string;
  friendshipId: string;
}
export interface Message {
  id: string;
  senderUid: string;
  senderUsername: string;
  body: string;
  deleted: boolean;
  clientSentAt: number;
}
export const pairId = (a: string, b: string) => [a, b].sort().join("--");
export async function register(identity: Identity) {
  const { db } = await firebase();
  await setDoc(
    doc(db, "messageDirectory", identity.username.toLowerCase()),
    {
      uid: identity.uid,
      username: identity.username.toLowerCase(),
      updatedAt: serverTimestamp(),
    },
    { merge: true },
  );
}
export async function searchUser(
  username: string,
  currentUid: string,
): Promise<DirectoryUser | null> {
  if (!/^[a-z0-9_]{4,24}$/.test(username.trim().toLowerCase()))
    throw new Error("Enter the exact 4–24 character username.");
  const { db } = await firebase();
  const row = await getDoc(
    doc(db, "messageDirectory", username.trim().toLowerCase()),
  );
  const value = row.data() as DirectoryUser | undefined;
  return value && value.uid !== currentUid ? value : null;
}
export async function subscribeSocial(
  uid: string,
  callback: (value: {
    friends: Friend[];
    incoming: Request[];
    outgoing: Request[];
  }) => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  const value: { friends: Friend[]; incoming: Request[]; outgoing: Request[] } =
    { friends: [], incoming: [], outgoing: [] };
  const cleanups = [
    onSnapshot(
      collection(db, "friendLists", uid, "friends"),
      (s) => {
        value.friends = s.docs.map((d) => d.data() as Friend);
        callback({ ...value });
      },
      onError,
    ),
    ...(["recipientUid", "senderUid"] as const).map((field, index) =>
      onSnapshot(
        query(collection(db, "friendRequests"), where(field, "==", uid)),
        (s) => {
          value[index === 0 ? "incoming" : "outgoing"] = s.docs.map(
            (d) => ({ ...d.data(), id: d.id }) as Request,
          );
          callback({ ...value });
        },
        onError,
      ),
    ),
  ];
  return () => cleanups.forEach((stop) => stop());
}
export async function sendRequest(current: Identity, peer: DirectoryUser) {
  const { db } = await firebase();
  const id = `${current.uid}--${peer.uid}`;
  await setDoc(doc(db, "friendRequests", id), {
    senderUid: current.uid,
    senderUsername: current.username.toLowerCase(),
    recipientUid: peer.uid,
    recipientUsername: peer.username,
    friendshipId: pairId(current.uid, peer.uid),
    createdAt: serverTimestamp(),
  });
}
export async function acceptRequest(current: Identity, request: Request) {
  if (request.recipientUid !== current.uid)
    throw new Error("Only the recipient can accept.");
  const { db } = await firebase();
  const id = request.friendshipId;
  const participantIds = [current.uid, request.senderUid].sort();
  const participantUsernames = {
    [current.uid]: current.username.toLowerCase(),
    [request.senderUid]: request.senderUsername,
  };
  const batch = writeBatch(db);
  batch.set(doc(db, "friendships", id), {
    participantIds,
    participantUsernames,
    acceptedRequestId: request.id,
    createdAt: serverTimestamp(),
  });
  batch.set(doc(db, "friendLists", current.uid, "friends", id), {
    friendshipId: id,
    friendUid: request.senderUid,
    friendUsername: request.senderUsername,
    createdAt: serverTimestamp(),
  });
  batch.set(doc(db, "friendLists", request.senderUid, "friends", id), {
    friendshipId: id,
    friendUid: current.uid,
    friendUsername: current.username.toLowerCase(),
    createdAt: serverTimestamp(),
  });
  batch.set(doc(db, "conversations", id), {
    friendshipId: id,
    participantIds,
    participantUsernames,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    lastMessage: "",
    lastMessageId: "",
    lastSenderUid: "",
  });
  batch.delete(doc(db, "friendRequests", request.id));
  await batch.commit();
}
export async function dismissRequest(request: Request) {
  const { db } = await firebase();
  await deleteDoc(doc(db, "friendRequests", request.id));
}
export async function subscribeMessages(
  id: string,
  callback: (messages: Message[]) => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  return onSnapshot(
    query(
      collection(db, "conversations", id, "messages"),
      orderBy("sentAt"),
      limitToLast(250),
    ),
    (s) => callback(s.docs.map((d) => ({ ...d.data(), id: d.id }) as Message)),
    onError,
  );
}
export async function sendMessage(id: string, current: Identity, text: string) {
  const body = text.trim().replace(/\r\n/g, "\n");
  if (!body || body.length > 2000)
    throw new Error("Messages must contain 1–2,000 characters.");
  const { db } = await firebase();
  const conversation = doc(db, "conversations", id);
  const message = doc(collection(conversation, "messages"));
  const batch = writeBatch(db);
  batch.set(message, {
    senderUid: current.uid,
    senderUsername: current.username.toLowerCase(),
    body,
    deleted: false,
    sentAt: serverTimestamp(),
    clientSentAt: Date.now(),
  });
  batch.update(conversation, {
    lastMessage: body.slice(0, 160),
    lastMessageId: message.id,
    lastSenderUid: current.uid,
    updatedAt: serverTimestamp(),
  });
  await batch.commit();
}
export async function unsendMessage(
  id: string,
  message: Message,
  current: Identity,
) {
  if (message.senderUid !== current.uid)
    throw new Error("You can only unsend your own messages.");
  const { db } = await firebase();
  const conversation = doc(db, "conversations", id);
  const messageRef = doc(conversation, "messages", message.id);
  await runTransaction(db, async (tx) => {
    const original = await tx.get(messageRef);
    const chat = await tx.get(conversation);
    if (original.data()?.senderUid !== current.uid || original.data()?.deleted)
      throw new Error("This message cannot be unsent.");
    tx.update(messageRef, {
      body: "Message unsent",
      deleted: true,
      deletedAt: serverTimestamp(),
    });
    if (chat.data()?.lastMessageId === message.id)
      tx.update(conversation, {
        lastMessage: "Message unsent",
        updatedAt: serverTimestamp(),
      });
  });
}
