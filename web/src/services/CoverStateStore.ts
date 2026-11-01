// Web app users: saves the selected web cover and its settings.
import { database } from "./LocalDataStore";
import { encrypt, decrypt } from "./EncryptionService";
import type { Envelope, Event } from "../domain/DomainModels";
let keyPromise: Promise<CryptoKey> | undefined;
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
// Reads cover.
export async function readCover<T>(id: string, fallback: T): Promise<T> {
  const box: Envelope | undefined = await (
    await database
  ).get("device", `cover:${id}`);
  return box ? decrypt<T>(await deviceKey(), box) : fallback;
}
export async function writeCover(id: string, value: unknown) {
  await (
    await database
  ).put("device", await encrypt(await deviceKey(), value), `cover:${id}`);
}
export async function pendingCheckout(uid: string, event: Event) {
  const rows = await readCover<Event[]>(`${uid}:checkouts`, []);
  await writeCover(`${uid}:checkouts`, [event, ...rows].slice(0, 100));
}
