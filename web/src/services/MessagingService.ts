// Web app users: reads and writes friend requests and encrypted chats.
import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc,
  deleteDoc,
  onSnapshot,
  query,
  where,
  orderBy,
  limitToLast,
  limit,
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
  ticketNumber?: number;
}
// Makes one chat ID from two account IDs. Sorting them means both users get the same ID.
export const pairId = (a: string, b: string) => [a, b].sort().join("--");
// Turns encrypted bytes into text so Firebase can store them safely.
const bytesToBase64 = (value: ArrayBuffer | Uint8Array) => {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  bytes.forEach((byte) => (binary += String.fromCharCode(byte)));
  return btoa(binary);
};
// Turns saved Base64 text back into bytes so the browser can decrypt it.
const base64ToBytes = (value: string) =>
  Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
// Loads this browser's chat keys. A new key pair is made and saved on this device when none exists.
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
// Exports the public chat key as text so other devices can use it to share a room key.
async function encodedPublicKey(uid: string) {
  const pair = await messagingKeys(uid);
  return bytesToBase64(await crypto.subtle.exportKey("spki", pair.publicKey));
}
// Builds a safe ID for this browser from its public key, without exposing the private key.
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
// Reads another device's public key so this browser can encrypt a room key for it.
async function importPublicKey(value: string, hash = "SHA-256") {
  return crypto.subtle.importKey(
    "spki",
    base64ToBytes(value),
    { name: "RSA-OAEP", hash },
    false,
    ["encrypt"],
  );
}
// Locks a chat room key with one device's public key so only that device can open it.
async function wrapRoomKey(raw: Uint8Array, publicKey: string, hash = "SHA-256") {
  return bytesToBase64(
    await crypto.subtle.encrypt(
      { name: "RSA-OAEP" },
      await importPublicKey(publicKey, hash),
      raw.buffer.slice(
        raw.byteOffset,
        raw.byteOffset + raw.byteLength,
      ) as ArrayBuffer,
    ),
  );
}
// Opens a saved room key with this browser's private key and returns its raw bytes.
export async function unwrapRoomKeyBytes(uid: string, value: string) {
  const pair = await messagingKeys(uid);
  return new Uint8Array(await crypto.subtle.decrypt(
    { name: "RSA-OAEP" },
    pair.privateKey,
    base64ToBytes(value),
  ));
}
// Turns the opened room-key bytes into the AES key used to read and send chat messages.
export async function unwrapRoomKey(uid: string, value: string) {
  const raw = await unwrapRoomKeyBytes(uid, value);
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}
// Registers this account and browser for secure chat. It saves only the public key in Firebase.
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

export async function prepareSupportTickets() {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  await httpsCallable(functions, "supportTicketsPrepare")();
}
// Finds user for the web data service.
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
// Keeps the friends and request lists up to date. Three Firebase listeners are closed when the page leaves.
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
// Sends a friend request by saving both account names and their shared friendship ID.
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
// Accepts a friend request and starts a blank direct chat. One batch keeps both friend lists in sync.
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
// Removes a friend request that was declined or cancelled.
export async function dismissRequest(request: Request) {
  const { db } = await firebase();
  await deleteDoc(doc(db, "friendRequests", request.id));
}
// Watches the newest chat messages and decrypts each one with the room key before showing it.
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
          // Deleted messages stay blank. All other saved messages are decrypted before they are shown.
          if (!value.deleted && value.ciphertext) {
            // A bad or missing key should not crash the chat, so show a clear message when decryption fails.
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
// Watches every chat that includes this account and keeps the newest chats at the top.
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
        ticketNumber: Number.isSafeInteger(data.ticketNumber) && data.ticketNumber >= 0 ? data.ticketNumber : undefined,
      } as Conversation;
    }).sort((a, b) => b.updatedAt - a.updatedAt)),
    onError,
  );
}
// Chooses the chat title. Users see an anonymous Olive Staff label, while staff see the user's name.
export function conversationName(conversation: Conversation, current: Identity) {
  if (conversation.type === "SUPPORT") {
    const label = conversation.supportTag === "NEEDS_ADMIN" ? "Admin Ticket"
      : conversation.supportOrigin === "PLAN_CONSULT" || conversation.supportTag === "PLAN_CONSULT"
        ? "Consult Ticket" : "Support Ticket";
    return conversation.ticketNumber === undefined ? label : `${label} #${conversation.ticketNumber}`;
  }
  return Object.entries(conversation.participantUsernames)
    .find(([uid]) => uid !== current.uid)?.[1] ?? "Conversation";
}
// Gets the secure key for a chat. It reuses this browser's key or creates one key for every approved participant.
export async function roomKey(id: string, current: Identity, conversationData: Conversation) {
  await register(current);
  const { db, functions } = await firebase();
  if (conversationData.type === "SUPPORT") {
    const { httpsCallable } = await import("firebase/functions");
    const result = await httpsCallable<{ conversationId: string }, { participantUsernames: Record<string, string> }>(
      functions, "conversationPrepareKeys",
    )({ conversationId: id });
    conversationData = { ...conversationData, participantUsernames: result.data.participantUsernames };
  }
  const device = await messageDevice(current.uid);
  const deviceKeyReference = doc(
    db,
    "conversations",
    id,
    "deviceKeys",
    `${current.uid}--${device.deviceId}`,
  );
  const savedDeviceKey = await getDoc(deviceKeyReference);
  if (savedDeviceKey.exists()) {
    const raw = await unwrapRoomKeyBytes(current.uid, String(savedDeviceKey.data().wrappedKey)).catch(() => null);
    if (raw) {
      try {
        const key = await crypto.subtle.importKey("raw", raw.slice().buffer, "AES-GCM", false, ["encrypt", "decrypt"]);
        const sharingKey = raw.slice();
        void shareRoomKeyWithDevices(id, conversationData, sharingKey)
          .catch(() => { /* Another device can retry approval on its next open. */ })
          .finally(() => sharingKey.fill(0));
        return key;
      } finally {
        raw.fill(0);
      }
    }
  }
  const directory = await Promise.all(conversationData.participantIds.map(async (uid) => {
    const username = conversationData.participantUsernames[uid];
    if (!username) return [uid, ""] as const;
    const row = await getDoc(doc(db, "messageDirectory", username.toLowerCase()));
    return [uid, row.get("uid") === uid ? String(row.data()?.publicKey ?? "") : ""] as const;
  }));
  const publicKeys: Record<string, string> = Object.fromEntries(directory);
  if (Object.values(publicKeys).some((key) => !key))
    throw new Error(
      "Every person in this chat must open the updated app before secure messages can be sent.",
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
  const messages = await getDocs(query(collection(conversation, "messages"), limit(1)));
  try {
    const selected = await runTransaction(db, async (transaction) => {
    const snapshot = await transaction.get(conversation);
    if (!snapshot.exists())
      throw new Error("This conversation is no longer available.");
    const savedKeys = snapshot.data().participantPublicKeys as
      Record<string, string> | undefined;
    const roomKeys = snapshot.data().roomKeys as
      Record<string, string> | undefined;
    // Existing room keys may be used only when this browser's public key was already approved.
    if (savedKeys && roomKeys) {
      if (savedKeys[current.uid] === device.publicKey && roomKeys[current.uid])
        return roomKeys[current.uid];
      if (!messages.empty || snapshot.get("lastMessageId")) throw new Error(
        "Open this chat on an existing authorized device to approve this browser.",
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
    const selectedRaw = await unwrapRoomKeyBytes(current.uid, selected);
    try {
      await shareRoomKeyWithDevices(id, conversationData, selectedRaw);
      return await crypto.subtle.importKey("raw", selectedRaw.slice().buffer, "AES-GCM", false, ["encrypt", "decrypt"]);
    } finally {
      selectedRaw.fill(0);
    }
  } finally {
    raw.fill(0);
  }
}

// Publish an envelope for every registered device so either platform can open a new support ticket.
async function shareRoomKeyWithDevices(id: string, conversation: Conversation, raw: Uint8Array) {
  const { db } = await firebase();
  for (const uid of conversation.participantIds) {
    const username = conversation.participantUsernames[uid]?.trim().toLowerCase();
    if (!username) continue;
    const devices = await getDocs(collection(db, "messageDirectory", username, "devices"));
    let batch = writeBatch(db);
    let count = 0;
    for (const device of devices.docs) {
      const value = device.data();
      if (value.uid !== uid || typeof value.publicKey !== "string" || typeof value.deviceId !== "string") continue;
      batch.set(doc(db, "conversations", id, "deviceKeys", `${uid}--${value.deviceId}`), {
        uid, deviceId: value.deviceId, publicKey: value.publicKey,
        wrappedKey: await wrapRoomKey(raw, value.publicKey, value.mgf1Digest === "SHA-256" ? "SHA-256" : "SHA-1"),
        updatedAt: serverTimestamp(),
      });
      count++;
      if (count === 10) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count) await batch.commit();
  }
}
// Encrypts a message on this browser, then saves only the locked text in Firebase.
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
// Un-sends the account's own message. The locked text is replaced and the chat preview is updated.
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
