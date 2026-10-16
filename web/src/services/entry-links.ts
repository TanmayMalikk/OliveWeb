import { cloudMode, firebase } from "./accounts";
import { database } from "./local";
import { readCover, writeCover } from "./cover-storage";
import { toBase64 } from "./crypto";
import { entryLinkUrl } from "../sites";
export const validEntryToken = (token: string) =>
  /^[A-Za-z0-9_-]{43}$/.test(token);
export async function issueEntryLink(uid: string) {
  const token = toBase64(crypto.getRandomValues(new Uint8Array(32)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
  const hash = await tokenDigest(token);
  if (cloudMode) {
    const { db, auth } = await firebase();
    if (auth.currentUser?.uid !== uid)
      throw new Error("Sign in again to create a link.");
    const { doc, setDoc, serverTimestamp } = await import("firebase/firestore");
    await setDoc(doc(db, "webEntryLinks", uid), {
      tokenHash: hash,
      updatedAt: serverTimestamp(),
    });
  }
  await (await database).put("device", hash, `entry-hash:${uid}`);
  await writeCover(`${uid}:personal-link`, token);
  return entryLinkUrl(token, location.origin);
}
export async function tokenDigest(token: string) {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)),
    ),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}
export async function verifyEntryLink(
  uid: string,
  token: string,
  localOnly = false,
) {
  if (!validEntryToken(token)) return false;
  const hash = await tokenDigest(token);
  if (cloudMode && !localOnly) {
    const { db, auth } = await firebase();
    if (auth.currentUser?.uid !== uid) return false;
    const { doc, getDoc } = await import("firebase/firestore");
    const saved = await getDoc(doc(db, "webEntryLinks", uid));
    const valid = saved.data()?.tokenHash === hash;
    if (valid) await (await database).put("device", hash, `entry-hash:${uid}`);
    return valid;
  }
  return (await (await database).get("device", `entry-hash:${uid}`)) === hash;
}
export async function savedEntryLink(uid: string) {
  const token = await readCover<string | null>(`${uid}:personal-link`, null);
  if (!token) return null;
  return (await verifyEntryLink(uid, token))
    ? entryLinkUrl(token, location.origin)
    : null;
}
