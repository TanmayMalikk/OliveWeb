// Web app users: reads and writes friend requests and encrypted chats.
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
import { firebase } from "./AccountService";
import { database } from "./LocalDataStore";
import type { Identity } from "../domain/DomainModels";
export interface DirectoryUser {
  uid: string;
  username: string;
  publicKey: string;
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
  ciphertext?: string;
  deleted: boolean;
  clientSentAt: number;
}
export interface Conversation {
  id: string;
  friendshipId: string;
  participantIds: string[];
  participantUsernames: Record<string, string>;
  lastMessage: string;
  updatedAt: number;
  type: "DIRECT" | "SUPPORT";
  ownerUid: string;
  staffUid: string;
  adminUid: string;
  supportOrigin: "" | "PLAN_CONSULT" | "SUPPORT";
  supportTag: "" | "PLAN_CONSULT" | "SUPPORT" | "NEEDS_ADMIN";
  closedAt: number;
}
export const pairId = (a: string, b: string) => [a, b].sort().join("--");
const bytesToBase64 = (value: ArrayBuffer | Uint8Array) => {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  bytes.forEach((byte) => (binary += String.fromCharCode(byte)));
  return btoa(binary);
};
const base64ToBytes = (value: string) =>
  Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
async function messagingKeys(uid: string): Promise<CryptoKeyPair> {
  const db = await database;
  const id = `message-keys:${uid}`;
  const saved = await db.get("device", id);
  if (saved?.publicKey && saved?.privateKey) return saved as CryptoKeyPair;
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSA-OAEP",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    false,
    ["encrypt", "decrypt"],
  )) as CryptoKeyPair;
  await db.put("device", pair, id);
  return pair;
}
async function encodedPublicKey(uid: string) {
  const pair = await messagingKeys(uid);
  return bytesToBase64(await crypto.subtle.exportKey("spki", pair.publicKey));
}
export async function messageDevice(uid: string) {
  const publicKey = await encodedPublicKey(uid);
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", base64ToBytes(publicKey)),
  );
  const deviceId = bytesToBase64(digest.slice(0, 18))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
  return { deviceId, publicKey };
}
async function importPublicKey(value: string) {
  return crypto.subtle.importKey(
    "spki",
    base64ToBytes(value),
    { name: "RSA-OAEP", hash: "SHA-256" },
    false,
    ["encrypt"],
  );
}
async function wrapRoomKey(raw: Uint8Array, publicKey: string) {
  return bytesToBase64(
    await crypto.subtle.encrypt(
      { name: "RSA-OAEP" },
      await importPublicKey(publicKey),
      raw.buffer.slice(
        raw.byteOffset,
        raw.byteOffset + raw.byteLength,
      ) as ArrayBuffer,
    ),
  );
}
export async function unwrapRoomKey(uid: string, value: string) {
  const pair = await messagingKeys(uid);
  const raw = await crypto.subtle.decrypt(
    { name: "RSA-OAEP" },
    pair.privateKey,
    base64ToBytes(value),
  );
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}
export async function register(identity: Identity) {
  const { db } = await firebase();
  const { deviceId, publicKey } = await messageDevice(identity.uid);
  const reference = doc(db, "messageDirectory", identity.username.toLowerCase());
  const existing = await getDoc(reference);
  if (existing.exists() && existing.data().uid !== identity.uid)
    throw new Error("That username belongs to another account.");
  if (!existing.exists())
    await setDoc(reference, {
        uid: identity.uid,
        username: identity.username.toLowerCase(),
        publicKey,
        updatedAt: serverTimestamp(),
      });
  await setDoc(doc(reference, "devices", deviceId), {
    uid: identity.uid,
    deviceId,
    publicKey,
    platform: "WEB",
    mgf1Digest: "SHA-256",
    updatedAt: serverTimestamp(),
  }, { merge: true });
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
// Sends request.
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
  roomKey: CryptoKey,
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
    async (snapshot) => {
      const messages = await Promise.all(
        snapshot.docs.map(async (row) => {
          const value = row.data() as Omit<Message, "id" | "body">;
          let body = "";
          if (!value.deleted && value.ciphertext) {
            try {
              const combined = base64ToBytes(value.ciphertext);
              const plain = await crypto.subtle.decrypt(
                { name: "AES-GCM", iv: combined.slice(0, 12) },
                roomKey,
                combined.slice(12),
              );
              body = new TextDecoder().decode(plain);
            } catch {
              body = "Unable to decrypt this message on this device.";
            }
          }
          return { ...value, id: row.id, body } as Message;
        }),
      );
      callback(messages);
    },
    onError,
  );
}
export async function subscribeConversations(
  uid: string,
  callback: (conversations: Conversation[]) => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  return onSnapshot(
    query(collection(db, "conversations"), where("participantIds", "array-contains", uid)),
    (snapshot) => callback(snapshot.docs.map((row) => {
      const data = row.data();
      return {
        id: row.id,
        friendshipId: String(data.friendshipId ?? row.id),
        participantIds: Array.isArray(data.participantIds) ? data.participantIds : [],
        participantUsernames: data.participantUsernames ?? {},
        lastMessage: String(data.lastMessage ?? ""),
        updatedAt: data.updatedAt?.toMillis?.() ?? 0,
        type: data.type === "SUPPORT" ? "SUPPORT" : "DIRECT",
        ownerUid: String(data.ownerUid ?? ""),
        staffUid: String(data.staffUid ?? ""),
        adminUid: String(data.adminUid ?? ""),
        supportOrigin: data.supportOrigin ?? "",
        supportTag: data.supportTag ?? "",
        closedAt: data.closedAt?.toMillis?.() ?? 0,
      } as Conversation;
    }).sort((a, b) => b.updatedAt - a.updatedAt)),
    onError,
  );
}
export function conversationName(conversation: Conversation, current: Identity) {
  if (conversation.type === "SUPPORT" && current.role !== "STAFF" && current.role !== "ADMIN") {
    const label = conversation.supportTag === "NEEDS_ADMIN"
      ? "Escalation"
      : conversation.supportOrigin === "PLAN_CONSULT"
        ? "Plan Consult"
        : "Support";
    return `Olive Staff • ${label}`;
  }
  if (conversation.type === "SUPPORT")
    return conversation.participantUsernames[conversation.ownerUid] || "User";
  return Object.entries(conversation.participantUsernames)
    .find(([uid]) => uid !== current.uid)?.[1] ?? "Conversation";
}
export async function roomKey(id: string, current: Identity, conversationData: Conversation) {
  const { db } = await firebase();
  const device = await messageDevice(current.uid);
  const deviceKeyReference = doc(
    db,
    "conversations",
    id,
    "deviceKeys",
    `${current.uid}--${device.deviceId}`,
  );
  const savedDeviceKey = await getDoc(deviceKeyReference);
  if (savedDeviceKey.exists())
    return unwrapRoomKey(current.uid, String(savedDeviceKey.data().wrappedKey));
  const directory = await Promise.all(conversationData.participantIds.map(async (uid) => {
    const username = conversationData.participantUsernames[uid];
    if (!username) return [uid, ""] as const;
    const row = await getDoc(doc(db, "messageDirectory", username.toLowerCase()));
    return [uid, String(row.data()?.publicKey ?? "")] as const;
  }));
  const publicKeys: Record<string, string> = Object.fromEntries(directory);
  if (Object.values(publicKeys).some((key) => !key))
    throw new Error(
      "Both people must open the updated app before secure messages can be sent.",
    );
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const wrapped = Object.fromEntries(
    await Promise.all(
      Object.entries(publicKeys).map(async ([uid, key]) => [
        uid,
        await wrapRoomKey(raw, key),
      ]),
    ),
  );
  const ownDeviceWrapped = await wrapRoomKey(raw, device.publicKey);
  const conversation = doc(db, "conversations", id);
  const selected = await runTransaction(db, async (transaction) => {
    const snapshot = await transaction.get(conversation);
    if (!snapshot.exists())
      throw new Error("This conversation is no longer available.");
    const savedKeys = snapshot.data().participantPublicKeys as
      Record<string, string> | undefined;
    const roomKeys = snapshot.data().roomKeys as
      Record<string, string> | undefined;
    if (savedKeys && roomKeys) {
      if (savedKeys[current.uid] === device.publicKey && roomKeys[current.uid])
        return roomKeys[current.uid];
      throw new Error(
        "Open this chat once on Android to securely approve this browser.",
      );
    }
    transaction.update(conversation, {
      roomKeys: wrapped,
      participantPublicKeys: publicKeys,
      updatedAt: serverTimestamp(),
    });
    return ownDeviceWrapped;
  });
  await setDoc(deviceKeyReference, {
    uid: current.uid,
    deviceId: device.deviceId,
    publicKey: device.publicKey,
    wrappedKey: selected,
    updatedAt: serverTimestamp(),
  });
  raw.fill(0);
  return unwrapRoomKey(current.uid, selected);
}
// Sends message.
export async function sendMessage(
  id: string,
  current: Identity,
  text: string,
  key: CryptoKey,
) {
  const body = text.trim().replace(/\r\n/g, "\n");
  if (!body || body.length > 2000)
    throw new Error("Messages must contain 1–2,000 characters.");
  const { db } = await firebase();
  const conversation = doc(db, "conversations", id);
  const message = doc(collection(conversation, "messages"));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      key,
      new TextEncoder().encode(body),
    ),
  );
  const combined = new Uint8Array(iv.length + encrypted.length);
  combined.set(iv);
  combined.set(encrypted, iv.length);
  const batch = writeBatch(db);
  batch.set(message, {
    senderUid: current.uid,
    senderUsername: current.username.toLowerCase(),
    ciphertext: bytesToBase64(combined),
    deleted: false,
    sentAt: serverTimestamp(),
    clientSentAt: Date.now(),
  });
  batch.update(conversation, {
    lastMessage: "Secure message",
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
      ...(original.data()?.ciphertext
        ? { ciphertext: "Message unsent" }
        : { body: "Message unsent" }),
      deleted: true,
      deletedAt: serverTimestamp(),
    });
    if (chat.data()?.lastMessageId === message.id)
      tx.update(conversation, {
        lastMessage: "Secure message",
        updatedAt: serverTimestamp(),
      });
  });
}
