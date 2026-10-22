import { openDB } from "idb";
import { decrypt, encrypt, importKey, unwrapKey, wrapKey } from "./crypto";
import {
  emptyHistory,
  normalizeUsername,
  type Envelope,
  type History,
  type Identity,
  type Profile,
  type Role,
  type WrappedKey,
} from "../domain";
export const database = openDB("olive-branch-web", 1, {
  upgrade(db) {
    db.createObjectStore("accounts", { keyPath: "id" });
    db.createObjectStore("vaults");
    db.createObjectStore("device");
  },
});
interface LocalAccount {
  id: string;
  identity: Identity;
  passwordKey: WrappedKey;
}
export interface LocalVault {
  profile: Profile | null;
  history: History;
}
export async function createLocal(
  username: string,
  password: string,
  role: Role,
) {
  const db = await database;
  const id = `${role}:${normalizeUsername(username)}`;
  if (await db.get("accounts", id))
    throw new Error("That username is unavailable.");
  const identity = {
    uid: crypto.randomUUID(),
    username: normalizeUsername(username),
    role,
  };
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const record: LocalAccount = {
    id,
    identity,
    passwordKey: await wrapKey(password, raw),
  };
  await db.add("accounts", record);
  await writeLocal(identity.uid, raw, {
    profile: null,
    history: emptyHistory(),
  });
  return { identity, raw };
}
export async function loginLocal(
  username: string,
  password: string,
  role: Role,
) {
  const record: LocalAccount | undefined = await (
    await database
  ).get("accounts", `${role}:${normalizeUsername(username)}`);
  try {
    if (!record) {
      await wrapKey(password, crypto.getRandomValues(new Uint8Array(32)));
      throw new Error();
    }
    return {
      identity: record.identity,
      raw: await unwrapKey(password, record.passwordKey),
    };
  } catch {
    throw new Error(
      "Sign-in failed. Check your account type, username, and password.",
    );
  }
}
export async function readLocal(
  uid: string,
  raw: Uint8Array,
): Promise<LocalVault> {
  const box: Envelope = await (await database).get("vaults", uid);
  return box
    ? decrypt<LocalVault>(await importKey(raw), box)
    : { profile: null, history: emptyHistory() };
}
export async function writeLocal(
  uid: string,
  raw: Uint8Array,
  value: LocalVault,
) {
  await (
    await database
  ).put("vaults", await encrypt(await importKey(raw), value), uid);
}
export async function changeLocalPassword(
  identity: Identity,
  current: string,
  next: string,
) {
  const { raw } = await loginLocal(identity.username, current, identity.role);
  const db = await database;
  const id = `${identity.role}:${identity.username}`;
  const record: LocalAccount = await db.get("accounts", id);
  await db.put("accounts", {
    ...record,
    passwordKey: await wrapKey(next, raw),
  });
  raw.fill(0);
}
