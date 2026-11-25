// Decrypts shared private notes and incident reports stored in Firestore `privateEntries`.
import { doc, getDocFromServer } from "firebase/firestore";
import type { Entry, Identity } from "../domain/DomainModels";
import { accountKeysetWrappers } from "./AccountKeysetCrypto";
import { accountKeysetSession, firebase } from "./AccountService";
import { fromBase64, importKey } from "./EncryptionService";
import { messageDevice, unwrapRoomKeyBytes } from "./MessagingService";

type StoredPrivateEntry = {
  id?: unknown;
  ownerUid?: unknown;
  ciphertext?: unknown;
  wrappedKeys?: unknown;
  deviceWrappedKeys?: unknown;
  accountWrappedKeys?: unknown;
  allowedUsers?: unknown;
};

async function resolveSharedEntryKey(
  uid: string,
  deviceId: string,
  stored: StoredPrivateEntry,
): Promise<Uint8Array | null> {
  const deviceWrapped = stored.deviceWrappedKeys as Record<string, unknown> | undefined;
  const wrappedKeys = stored.wrappedKeys as Record<string, unknown> | undefined;
  const candidates = new Set([deviceWrapped?.[`${uid}--${deviceId}`], wrappedKeys?.[uid]]);
  for (const candidate of candidates) {
    if (typeof candidate !== "string" || !candidate) continue;
    const raw = await unwrapRoomKeyBytes(uid, candidate).catch(() => null);
    if (raw) return raw;
  }
  const accountWrapper = accountKeysetWrappers.parse(
    (stored.accountWrappedKeys as Record<string, unknown> | undefined)?.[uid],
  );
  return accountKeysetWrappers.unwrap(uid, accountWrapper, accountKeysetSession.current(uid));
}

/**
 * Decodes a Base64 `[12-byte IV || AES-GCM ciphertext]` payload into a normalized [Entry].
 */
export async function decryptSharedEntryPayload(
  entryId: string,
  encryptedBase64: string,
  rawKey: Uint8Array,
): Promise<Entry> {
  const combined = fromBase64(encryptedBase64);
  if (combined.length < 29) {
    throw new Error("Encrypted entry payload is invalid.");
  }
  const iv = combined.slice(0, 12);
  const ciphertext = combined.slice(12);
  const key = await importKey(rawKey);
  const plainBytes = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, tagLength: 128 },
    key,
    ciphertext,
  );
  const parsed = JSON.parse(new TextDecoder().decode(plainBytes)) as Record<string, unknown>;
  const attachments = Array.isArray(parsed.attachmentIds)
    ? [...new Set(parsed.attachmentIds.filter((id): id is string => typeof id === "string" && id.trim().length > 0))]
    : [];
  const kind = parsed.kind === "Incident" ? "Incident" : "Note";
  const now = Date.now();
  return {
    id: typeof parsed.id === "string" && parsed.id ? parsed.id : entryId,
    timestamp: typeof parsed.timestamp === "number" ? parsed.timestamp : now,
    updatedAt: typeof parsed.updatedAt === "number" ? parsed.updatedAt : now,
    kind,
    title: typeof parsed.title === "string" ? parsed.title : "",
    body: typeof parsed.body === "string" ? parsed.body : "",
    incidentDate: typeof parsed.incidentDate === "string" ? parsed.incidentDate : undefined,
    incidentTime: typeof parsed.incidentTime === "string" ? parsed.incidentTime : undefined,
    incidentLocation: typeof parsed.incidentLocation === "string" ? parsed.incidentLocation : undefined,
    incidentLatitude: typeof parsed.incidentLatitude === "number" ? parsed.incidentLatitude : undefined,
    incidentLongitude: typeof parsed.incidentLongitude === "number" ? parsed.incidentLongitude : undefined,
    attachmentIds: attachments.length ? attachments : undefined,
    pinned: parsed.pinned === true,
    edits: Array.isArray(parsed.edits) ? (parsed.edits as Entry["edits"]) : [],
  };
}

/**
 * Fetches and decrypts a private note or incident report shared with [identity] in Firestore.
 */
export async function loadSharedPrivateEntry(
  entryId: string,
  identity: Identity,
): Promise<Entry | null> {
  const cleanId = entryId.trim();
  if (!cleanId) return null;
  const { db } = await firebase();
  const snapshot = await getDocFromServer(doc(db, "privateEntries", cleanId));
  if (!snapshot.exists()) return null;
  const data = snapshot.data() as StoredPrivateEntry;
  if (typeof data.ciphertext !== "string" || !data.ciphertext) return null;
  const device = await messageDevice(identity.uid);
  const rawKey = await resolveSharedEntryKey(identity.uid, device.deviceId, data);
  if (!rawKey) return null;
  try {
    return await decryptSharedEntryPayload(cleanId, data.ciphertext, rawKey);
  } finally {
    rawKey.fill(0);
  }
}
