// Web safe-app users: lists and decrypts documents authorized for the signed-in account.
import { collection, getDocs, query, where } from "firebase/firestore";
import { firebase } from "./AccountService";
import { messageDevice, unwrapRoomKeyBytes } from "./MessagingService";
import { importKey, toBase64 } from "./EncryptionService";
import type { Identity } from "../domain/DomainModels";

type StoredMedia = {
  owner_id?: unknown;
  object_key?: unknown;
  encrypted_metadata?: unknown;
  wrapped_keys?: unknown;
  device_wrapped_keys?: unknown;
  allowed_users?: unknown;
  ciphertext_size?: unknown;
  ciphertext_sha256?: unknown;
  encryption_version?: unknown;
  created_at?: { toMillis?: () => number };
};

export type BrowserDocument = {
  id: string;
  name: string;
  mimeType: string;
  ownerUid: string;
  createdAt: number;
  approved: boolean;
  approvalMessage: string;
  ciphertextSize: number;
  ciphertextSha256: string;
  key?: CryptoKey;
};

// Creates a value from base64 for web media service. It checks access, protects the file, and then reads or saves it.
const fromBase64 = (value: string) =>
  Uint8Array.from(atob(value), (character) => character.charCodeAt(0));

// Decrypts metadata for web media service.
async function decryptMetadata(value: string, key: CryptoKey) {
  const payload = fromBase64(value);
  if (payload.length < 29) throw new Error("Encrypted document metadata is invalid.");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: payload.slice(0, 12), tagLength: 128 },
    key,
    payload.slice(12),
  );
  return JSON.parse(new TextDecoder().decode(plain)) as { name?: unknown; mimeType?: unknown };
}

// Lists browser documents for web media service. It sends a signed-in request to the protected cloud function and checks the reply.
export async function listBrowserDocuments(identity: Identity): Promise<BrowserDocument[]> {
  const { db, functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const device = await messageDevice(identity.uid);
  const deviceKey = `${identity.uid}--${device.deviceId}`;
  const snapshot = await getDocs(query(collection(db, "files"),
    where("allowed_users", "array-contains", identity.uid)));
  return Promise.all(snapshot.docs.map(async (row) => {
    const value = row.data() as StoredMedia;
    const deviceKeys = value.device_wrapped_keys as Record<string, unknown> | undefined;
    const wrapped = typeof deviceKeys?.[deviceKey] === "string" ? deviceKeys[deviceKey] : "";
    let key: CryptoKey | undefined;
    let name = "Encrypted document";
    let mimeType = "application/octet-stream";
    // Only continue with mime type when typeof value encrypted metadata is string.
    if (typeof value.encrypted_metadata === "string") {
      // If mime type fails, catch the error so the app can keep running and explain the problem.
      try {
        let raw: Uint8Array;
        // mime type checks whether wrapped before continuing.
        if (wrapped) {
          raw = await unwrapRoomKeyBytes(identity.uid, wrapped);
          // Only continue with mime type when value owner id is identity uid.
          if (value.owner_id === identity.uid) {
            void httpsCallable(functions, "mediaStoreAccountAccessKey")({
              mediaId: row.id,
              accountAccessKey: toBase64(raw),
            }).catch(() => undefined);
          }
        } else {
          const recovered = (await httpsCallable(functions, "mediaAccountAccessKey")({
            mediaId: row.id,
          })).data as { accountAccessKey?: unknown };
          if (typeof recovered.accountAccessKey !== "string")
            throw new Error("Account document key is unavailable.");
          raw = fromBase64(recovered.accountAccessKey);
        }
        key = await importKey(raw);
        raw.fill(0);
        const metadata = await decryptMetadata(value.encrypted_metadata, key);
        if (typeof metadata.name === "string" && metadata.name.trim()) name = metadata.name;
        if (typeof metadata.mimeType === "string" && metadata.mimeType.trim()) mimeType = metadata.mimeType;
      } catch {
        key = undefined;
      }
    }
    return {
      id: row.id,
      name,
      mimeType,
      ownerUid: typeof value.owner_id === "string" ? value.owner_id : "",
      createdAt: value.created_at?.toMillis?.() ?? 0,
      approved: Boolean(key),
      approvalMessage: key ? "" : "This older document has not yet been upgraded for account-wide access.",
      ciphertextSize: typeof value.ciphertext_size === "number" ? value.ciphertext_size : 0,
      ciphertextSha256: typeof value.ciphertext_sha256 === "string" ? value.ciphertext_sha256 : "",
      key,
    };
  })).then((rows) => rows.sort((a, b) => b.createdAt - a.createdAt));
}

// Web media service: returns the hexadecimal.
function hexadecimal(bytes: Uint8Array) {
  return [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
}

/** Downloads ciphertext directly from R2, verifies it, and decrypts only inside this browser. */
export async function openBrowserDocument(document: BrowserDocument) {
  if (!document.key) throw new Error(document.approvalMessage);
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const ticket = (await httpsCallable(functions, "mediaDownloadTicket")({ mediaId: document.id })).data as {
    url?: unknown; headers?: unknown;
  };
  if (typeof ticket.url !== "string" || !ticket.url.startsWith("https://"))
    throw new Error("The encrypted download ticket is invalid.");
  const response = await fetch(ticket.url, { headers: ticket.headers as HeadersInit | undefined });
  if (!response.ok) throw new Error(`Cloud download failed (${response.status}).`);
  const encrypted = new Uint8Array(await response.arrayBuffer());
  if (encrypted.length !== document.ciphertextSize)
    throw new Error("The encrypted file size does not match.");
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encrypted));
  if (hexadecimal(digest) !== document.ciphertextSha256)
    throw new Error("The encrypted file failed its integrity check.");
  if (new TextDecoder().decode(encrypted.slice(0, 4)) !== "OLVM" || encrypted[4] !== 1)
    throw new Error("This encrypted file format is not supported.");
  const ivLength = encrypted[5];
  if (ivLength !== 12 || encrypted.length <= 6 + ivLength + 16)
    throw new Error("The encrypted file header is invalid.");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: encrypted.slice(6, 6 + ivLength), tagLength: 128 },
    document.key,
    encrypted.slice(6 + ivLength),
  );
  return URL.createObjectURL(new Blob([plain], { type: document.mimeType }));
}
