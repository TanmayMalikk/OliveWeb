// Web app users: encrypts web chat content before storage.
import type { Envelope, WrappedKey } from "../domain/DomainModels";
const encoder = new TextEncoder();
export const toBase64 = (value: Uint8Array) =>
  btoa(Array.from(value, (b) => String.fromCharCode(b)).join(""));
export const fromBase64 = (value: string) =>
  Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
export async function triggerHash(value: string) {
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    encoder.encode(value.toLowerCase().replace(/\s/gu, "")),
  );
  return Array.from(new Uint8Array(bytes), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
}
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
export async function importKey(raw: Uint8Array) {
  return crypto.subtle.importKey("raw", raw as BufferSource, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}
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
export async function decrypt<T>(key: CryptoKey, box: Envelope): Promise<T> {
  const raw = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(box.iv) },
    key,
    fromBase64(box.data),
  );
  return JSON.parse(new TextDecoder().decode(raw)) as T;
}
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
export async function unwrapKey(secret: string, wrapped: WrappedKey) {
  return fromBase64(
    await decrypt<string>(
      await deriveKey(secret, fromBase64(wrapped.salt)),
      wrapped.box,
    ),
  );
}
