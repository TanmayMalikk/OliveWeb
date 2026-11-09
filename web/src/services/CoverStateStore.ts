// Web app users: saves the selected web cover and its settings.
import { database } from "./LocalDataStore";
import { encrypt, decrypt } from "./EncryptionService";
import type { Envelope } from "../domain/DomainModels";
let keyPromise: Promise<CryptoKey> | undefined;
// Loads this browser's encryption key, or creates one the first time it is needed.
async function deviceKey() {
  keyPromise ??= (async () => {
    const db = await database;
    const existing = await db.get("device", "cover-key");
    if (existing) return existing as CryptoKey;
    const key = await crypto.subtle.generateKey(
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"],
    );
    await db.put("device", key, "cover-key");
    return key;
  })();
  return keyPromise;
}
// Decrypts one saved cover setting from this browser.
export async function readCover<T>(id: string, fallback: T): Promise<T> {
  const box: Envelope | undefined = await (
    await database
  ).get("device", `cover:${id}`);
  return box ? decrypt<T>(await deviceKey(), box) : fallback;
}
// Encrypts one cover setting before storing it in this browser.
export async function writeCover(id: string, value: unknown) {
  await (
    await database
  ).put("device", await encrypt(await deviceKey(), value), `cover:${id}`);
}
