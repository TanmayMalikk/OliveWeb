// Web safe-app users: lists and decrypts documents authorized for the signed-in account.
import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  where,
} from "firebase/firestore";
import { firebase } from "./AccountService";
import {
  messageDevice,
  register,
  unwrapRoomKeyBytes,
} from "./MessagingService";
import { importKey } from "./EncryptionService";
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

// Turns binary data into Base64 without adding line breaks.
const toBase64 = (value: Uint8Array) =>
  btoa(Array.from(value, (byte) => String.fromCharCode(byte)).join(""));

// Adds the Android-readable OLVM header to a browser-encrypted file.
async function encryptMediaFile(file: File, rawKey: Uint8Array) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const body = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, tagLength: 128 },
      await importKey(rawKey),
      await file.arrayBuffer(),
    ),
  );
  const result = new Uint8Array(6 + iv.length + body.length);
  result.set([0x4f, 0x4c, 0x56, 0x4d, 1, iv.length]);
  result.set(iv, 6);
  result.set(body, 6 + iv.length);
  return result;
}

// Wraps the file key for the owner's registered RSA key using Android's version 2 OAEP settings.
async function wrapForAccount(rawKey: Uint8Array, publicKeyText: string) {
  const publicKey = await crypto.subtle.importKey(
    "spki",
    fromBase64(publicKeyText),
    { name: "RSA-OAEP", hash: "SHA-1" },
    false,
    ["encrypt"],
  );
  const keyBytes = rawKey.slice().buffer as ArrayBuffer;
  return toBase64(
    new Uint8Array(
      await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, keyBytes),
    ),
  );
}

// Sends encrypted bytes to R2 using one short-lived ticket per upload part.
async function uploadCiphertext(
  jobId: string,
  ciphertext: Uint8Array,
  encryptedMetadata: string,
  wrappedKeys: Record<string, string>,
  uid: string,
  accountAccessKey: string,
  report: (fraction: number) => void,
) {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  let mediaId = "";
  try {
    const created = (
      await httpsCallable(
        functions,
        "mediaCreateUpload",
      )({
        jobId,
        ciphertextSize: ciphertext.byteLength,
        ciphertextSha256: await checksum(ciphertext),
        encryptedMetadata,
        wrappedKeys,
        allowedUsers: [uid],
        encryptionVersion: 2,
        accountAccessKey,
      })
    ).data as {
      mediaId: string;
      mode: "SINGLE" | "MULTIPART";
      partSize: number;
    };
    mediaId = created.mediaId;
    const partSize =
      created.mode === "SINGLE" ? ciphertext.byteLength : created.partSize;
    const parts: { partNumber: number; etag: string }[] = [];
    const count = Math.ceil(ciphertext.byteLength / partSize);
    for (let index = 0; index < count; index++) {
      const partNumber = index + 1;
      const ticket = (
        await httpsCallable(
          functions,
          "mediaUploadPartTicket",
        )({ jobId, partNumber })
      ).data as {
        url: string;
        headers?: Record<string, string>;
      };
      const start = index * partSize;
      const response = await fetch(ticket.url, {
        method: "PUT",
        headers: ticket.headers,
        body: ciphertext.slice(
          start,
          Math.min(start + partSize, ciphertext.byteLength),
        ),
      });
      if (!response.ok)
        throw new Error(`Encrypted upload failed (HTTP ${response.status}).`);
      if (created.mode === "MULTIPART") {
        const etag = response.headers.get("ETag");
        if (!etag)
          throw new Error(
            "Storage did not return the upload part receipt. Check the R2 CORS settings.",
          );
        parts.push({ partNumber, etag });
      }
      report((index + 1) / count);
    }
    await httpsCallable(functions, "mediaCompleteUpload")({ jobId, parts });
  } catch (error) {
    if (mediaId)
      await httpsCallable(
        functions,
        "mediaAbortUpload",
      )({ jobId }).catch(() => undefined);
    throw error;
  }
}

// Encrypts and saves one phone-or-web file to the same account vault as Android uploads.
export async function uploadBrowserDocument(
  file: File,
  identity: Identity,
  report: (fraction: number) => void = () => undefined,
) {
  if (!file.size || file.size > 248 * 1024 * 1024)
    throw new Error("Choose a file smaller than 248 MB.");
  await register(identity);
  const { db } = await firebase();
  const directory = await getDoc(
    doc(db, "messageDirectory", identity.username.toLowerCase()),
  );
  const publicKey = directory.data()?.publicKey;
  if (typeof publicKey !== "string" || !publicKey)
    throw new Error(
      "Open messaging once on this account before uploading files.",
    );

  const rawKey = crypto.getRandomValues(new Uint8Array(32));
  try {
    const ciphertext = await encryptMediaFile(file, rawKey);
    const metadata = new TextEncoder().encode(
      JSON.stringify({
        name: file.name.slice(0, 160) || "Document",
        mimeType: (file.type || "application/octet-stream").slice(0, 120),
        kind: file.type.startsWith("video/")
          ? "VIDEO"
          : file.type.startsWith("image/")
            ? "IMAGE"
            : "DOCUMENT",
        ocrReview: "NOT_RUN",
      }),
    );
    const metadataIv = crypto.getRandomValues(new Uint8Array(12));
    const encryptedMetadata = toBase64(
      new Uint8Array([
        ...metadataIv,
        ...new Uint8Array(
          await crypto.subtle.encrypt(
            { name: "AES-GCM", iv: metadataIv },
            await importKey(rawKey),
            metadata,
          ),
        ),
      ]),
    );
    const wrappedKey = await wrapForAccount(rawKey, publicKey);
    await uploadCiphertext(
      crypto.randomUUID(),
      ciphertext,
      encryptedMetadata,
      { [identity.uid]: wrappedKey },
      identity.uid,
      toBase64(rawKey),
      report,
    );
    rawKey.fill(0);
  } catch (error) {
    rawKey.fill(0);
    throw error;
  }
}

// Returns a lowercase SHA-256 checksum for the encrypted file.
async function checksum(bytes: Uint8Array) {
  const input = bytes.slice().buffer as ArrayBuffer;
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", input))]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

// Decrypts metadata for web media service.
async function decryptMetadata(value: string, key: CryptoKey) {
  const payload = fromBase64(value);
  if (payload.length < 29)
    throw new Error("Encrypted document metadata is invalid.");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: payload.slice(0, 12), tagLength: 128 },
    key,
    payload.slice(12),
  );
  return JSON.parse(new TextDecoder().decode(plain)) as {
    name?: unknown;
    mimeType?: unknown;
  };
}

// Lists browser documents for web media service. It sends a signed-in request to the protected cloud function and checks the reply.
export async function listBrowserDocuments(
  identity: Identity,
): Promise<BrowserDocument[]> {
  const { db, functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const device = await messageDevice(identity.uid);
  const deviceKey = `${identity.uid}--${device.deviceId}`;
  const snapshot = await getDocs(
    query(
      collection(db, "files"),
      where("allowed_users", "array-contains", identity.uid),
    ),
  );
  return Promise.all(
    snapshot.docs.map(async (row) => {
      const value = row.data() as StoredMedia;
      const deviceKeys = value.device_wrapped_keys as
        Record<string, unknown> | undefined;
      const wrapped =
        typeof deviceKeys?.[deviceKey] === "string"
          ? deviceKeys[deviceKey]
          : "";
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
              void httpsCallable(
                functions,
                "mediaStoreAccountAccessKey",
              )({
                mediaId: row.id,
                accountAccessKey: toBase64(raw),
              }).catch(() => undefined);
            }
          } else {
            const recovered = (
              await httpsCallable(
                functions,
                "mediaAccountAccessKey",
              )({
                mediaId: row.id,
              })
            ).data as { accountAccessKey?: unknown };
            if (typeof recovered.accountAccessKey !== "string")
              throw new Error("Account document key is unavailable.");
            raw = fromBase64(recovered.accountAccessKey);
          }
          key = await importKey(raw);
          raw.fill(0);
          const metadata = await decryptMetadata(value.encrypted_metadata, key);
          if (typeof metadata.name === "string" && metadata.name.trim())
            name = metadata.name;
          if (typeof metadata.mimeType === "string" && metadata.mimeType.trim())
            mimeType = metadata.mimeType;
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
        approvalMessage: key
          ? ""
          : "This older document has not yet been upgraded for account-wide access.",
        ciphertextSize:
          typeof value.ciphertext_size === "number" ? value.ciphertext_size : 0,
        ciphertextSha256:
          typeof value.ciphertext_sha256 === "string"
            ? value.ciphertext_sha256
            : "",
        key,
      };
    }),
  ).then((rows) => rows.sort((a, b) => b.createdAt - a.createdAt));
}

// Web media service: returns the hexadecimal.
function hexadecimal(bytes: Uint8Array) {
  return [...bytes]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

/** Downloads ciphertext directly from R2, verifies it, and decrypts only inside this browser. */
export async function openBrowserDocument(document: BrowserDocument) {
  if (!document.key) throw new Error(document.approvalMessage);
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const ticket = (
    await httpsCallable(
      functions,
      "mediaDownloadTicket",
    )({ mediaId: document.id })
  ).data as {
    url?: unknown;
    headers?: unknown;
  };
  if (typeof ticket.url !== "string" || !ticket.url.startsWith("https://"))
    throw new Error("The encrypted download ticket is invalid.");
  const response = await fetch(ticket.url, {
    headers: ticket.headers as HeadersInit | undefined,
  });
  if (!response.ok)
    throw new Error(`Cloud download failed (${response.status}).`);
  const encrypted = new Uint8Array(await response.arrayBuffer());
  if (encrypted.length !== document.ciphertextSize)
    throw new Error("The encrypted file size does not match.");
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", encrypted),
  );
  if (hexadecimal(digest) !== document.ciphertextSha256)
    throw new Error("The encrypted file failed its integrity check.");
  if (
    new TextDecoder().decode(encrypted.slice(0, 4)) !== "OLVM" ||
    encrypted[4] !== 1
  )
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
