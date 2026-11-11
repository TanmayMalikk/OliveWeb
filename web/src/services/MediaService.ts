// Web safe-app users: lists and decrypts documents authorized for the signed-in account.
import {
  collection,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  where,
  type DocumentData,
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
  evidence?: unknown;
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
  evidence: boolean;
  tags?: string[];
};

// Turns stored Base64 text back into bytes before a file is decrypted.
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
async function wrapForPublicKey(
  rawKey: Uint8Array,
  publicKeyText: string,
  hash: "SHA-1" | "SHA-256",
) {
  const publicKey = await crypto.subtle.importKey(
    "spki",
    fromBase64(publicKeyText),
    { name: "RSA-OAEP", hash },
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
  deviceWrappedKeys: Record<string, string>,
  uid: string,
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
        deviceWrappedKeys,
        allowedUsers: [uid],
        encryptionVersion: 2,
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
      let response: Response;
      try {
        response = await fetch(ticket.url, {
          method: "PUT",
          headers: ticket.headers,
          body: ciphertext.slice(
            start,
            Math.min(start + partSize, ciphertext.byteLength),
          ),
        });
      } catch (error) {
        const detail = error instanceof Error ? ` (${error.message})` : "";
        throw new Error(
          `The encrypted storage upload could not be reached${detail}. Check the R2 bucket CORS policy for this website origin, PUT requests, and the signed upload headers.`,
        );
      }
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
    return mediaId;
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
    const device = await messageDevice(identity.uid);
    const wrappedKey = await wrapForPublicKey(rawKey, publicKey, "SHA-1");
    const deviceWrappedKey = await wrapForPublicKey(rawKey, device.publicKey, "SHA-256");
    return await uploadCiphertext(
      crypto.randomUUID(),
      ciphertext,
      encryptedMetadata,
      { [identity.uid]: wrappedKey },
      { [`${identity.uid}--${device.deviceId}`]: deviceWrappedKey },
      identity.uid,
      report,
    );
  } finally {
    rawKey.fill(0);
  }
}

// Returns a lowercase SHA-256 checksum for the encrypted file.
async function checksum(bytes: Uint8Array) {
  const input = bytes.slice().buffer as ArrayBuffer;
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", input))]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

// Decrypts the file name, type, and other private document details.
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
    tags?: unknown;
  };
}

// Lists documents this signed-in account is allowed to open.
export async function listBrowserDocuments(
  identity: Identity,
): Promise<BrowserDocument[]> {
  const { db } = await firebase();
  const snapshot = await getDocs(
    query(
      collection(db, "files"),
      where("allowed_users", "array-contains", identity.uid),
    ),
  );
  return resolveBrowserDocuments(identity, snapshot.docs);
}

// Web safe-app documents: keeps phone and browser uploads visible as soon as Firestore records them.
export async function observeBrowserDocuments(
  identity: Identity,
  onUpdate: (documents: BrowserDocument[]) => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  const documentsQuery = query(
    collection(db, "files"),
    where("allowed_users", "array-contains", identity.uid),
  );
  return onSnapshot(
    documentsQuery,
    (snapshot) => {
      void resolveBrowserDocuments(identity, snapshot.docs)
        .then(onUpdate)
        .catch((error) => onError(error instanceof Error ? error : new Error(String(error))));
    },
    onError,
  );
}

async function resolveBrowserDocuments(
  identity: Identity,
  rows: Array<{ id: string; data: () => DocumentData }>,
): Promise<BrowserDocument[]> {
  const device = await messageDevice(identity.uid);
  const deviceKey = `${identity.uid}--${device.deviceId}`;
  return Promise.all(
    rows.map(async (row) => {
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
      let tags: string[] = [];
      // New uploads keep their private details in one encrypted metadata value.
      if (typeof value.encrypted_metadata === "string") {
        // Keep the document in the list when old metadata cannot be decrypted on this browser.
        try {
          if (!wrapped) throw new Error("This browser has no wrapped document key.");
          const raw = await unwrapRoomKeyBytes(identity.uid, wrapped);
          key = await importKey(raw);
          raw.fill(0);
          const metadata = await decryptMetadata(value.encrypted_metadata, key);
          if (typeof metadata.name === "string" && metadata.name.trim())
            name = metadata.name;
          if (typeof metadata.mimeType === "string" && metadata.mimeType.trim())
            mimeType = metadata.mimeType;
          if (Array.isArray(metadata.tags))
            tags = metadata.tags.filter((tag): tag is string => typeof tag === "string");
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
        evidence: value.evidence === true,
        tags,
      };
    }),
  ).then((rows) => rows.sort((a, b) => b.createdAt - a.createdAt));
}

// Documents: saves the Evidence toggle for the owner without opening the encrypted file.
export async function setBrowserDocumentEvidence(mediaId: string, evidence: boolean) {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  await httpsCallable(functions, "mediaUpdateEvidence")({ mediaId, evidence });
}

// Formats a checksum as lowercase hexadecimal for integrity checks.
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
