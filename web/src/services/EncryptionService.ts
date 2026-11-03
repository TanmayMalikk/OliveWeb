// Web app users: encrypts web chat content before storage.
import type { Envelope, WrappedKey } from "../domain/DomainModels";
const encoder = new TextEncoder();
// Turns encrypted bytes into Base64 text so they can be stored or sent.
export const toBase64 = (value: Uint8Array) =>
  btoa(Array.from(value, (b) => String.fromCharCode(b)).join(""));
// Turns stored Base64 text back into bytes for decryption.
export const fromBase64 = (value: string) =>
  Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
// Makes a one-way hash of the private trigger. The trigger itself is never saved.
export async function triggerHash(value: string) {
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    encoder.encode(value.toLowerCase().replace(/\s/gu, "")),
  );
  return Array.from(new Uint8Array(bytes), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
}
// Makes a strong encryption key from a secret and random salt. Repeating the work slows password guessing.
export async function deriveKey(secret: string, salt: Uint8Array) {
  const source = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: salt as BufferSource,
      iterations: 600_000,
    },
    source,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}
// Loads raw key bytes into the browser's AES encryption tool.
export async function importKey(raw: Uint8Array) {
  return crypto.subtle.importKey("raw", raw as BufferSource, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}
// Locks a value with AES-GCM. A new random IV keeps two matching values from looking the same.
export async function encrypt(
  key: CryptoKey,
  value: unknown,
): Promise<Envelope> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    encoder.encode(JSON.stringify(value)),
  );
  return { iv: toBase64(iv), data: toBase64(new Uint8Array(data)) };
}
// Opens an encrypted value with its key, then turns the JSON text back into data.
export async function decrypt<T>(key: CryptoKey, box: Envelope): Promise<T> {
  const raw = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(box.iv) },
    key,
    fromBase64(box.data),
  );
  return JSON.parse(new TextDecoder().decode(raw)) as T;
}
// Protects a raw key with a user secret so the raw key is not saved in plain text.
export async function wrapKey(
  secret: string,
  raw: Uint8Array,
): Promise<WrappedKey> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return {
    salt: toBase64(salt),
    box: await encrypt(await deriveKey(secret, salt), toBase64(raw)),
  };
}
// Opens a protected key with the same user secret and salt that locked it.
export async function unwrapKey(secret: string, wrapped: WrappedKey) {
  return fromBase64(
    await decrypt<string>(
      await deriveKey(secret, fromBase64(wrapped.salt)),
      wrapped.box,
    ),
  );
}
