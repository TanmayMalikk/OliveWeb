// Web safe-app users: lists and decrypts documents approved for this browser.
import { collection, getDocs, query, where } from "firebase/firestore";
import { firebase } from "./AccountService";
import { messageDevice, unwrapRoomKey } from "./MessagingService";
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

const fromBase64 = (value: string) =>
  Uint8Array.from(atob(value), (character) => character.charCodeAt(0));

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

export async function listBrowserDocuments(identity: Identity): Promise<BrowserDocument[]> {
  const { db } = await firebase();
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
    if (wrapped && typeof value.encrypted_metadata === "string") {
      try {
        key = await unwrapRoomKey(identity.uid, wrapped);
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
      approvalMessage: key ? "" : "Open Documents on an already approved Android device once to approve this browser.",
      ciphertextSize: typeof value.ciphertext_size === "number" ? value.ciphertext_size : 0,
      ciphertextSha256: typeof value.ciphertext_sha256 === "string" ? value.ciphertext_sha256 : "",
      key,
    };
  })).then((rows) => rows.sort((a, b) => b.createdAt - a.createdAt));
}

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
