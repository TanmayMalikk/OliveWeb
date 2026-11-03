// Web app users: creates discreet safe-app entry links.
import { cloudMode, firebase } from "./AccountService";
import { database } from "./LocalDataStore";
import { readCover, writeCover } from "./CoverStateStore";
import { toBase64 } from "./EncryptionService";
import { entryLinkUrl } from "../sites/SiteRouting";
import { tokenDigest, validEntryToken } from "./EntryTokenPolicy";
export { tokenDigest, validEntryToken } from "./EntryTokenPolicy";
// returns the issue entry link for web private entry links.
export async function issueEntryLink(uid: string) {
  const token = toBase64(crypto.getRandomValues(new Uint8Array(32)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
  const hash = await tokenDigest(token);
  // cloud mode is handled here by hash.
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
// Web private entry links: returns the verify entry link.
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
// Web private entry links: returns the saved entry link.
export async function savedEntryLink(uid: string) {
  const token = await readCover<string | null>(`${uid}:personal-link`, null);
  if (!token) return null;
  return (await verifyEntryLink(uid, token))
    ? entryLinkUrl(token, location.origin)
    : null;
}
