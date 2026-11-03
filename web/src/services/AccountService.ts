// Web app users: signs in accounts and reads their stored profile data.
import {
  createLocal,
  loginLocal,
  readLocal,
  writeLocal,
  database,
  changeLocalPassword,
} from "./LocalDataStore";
import { unwrapKey, wrapKey, triggerHash } from "./EncryptionService";
import {
  emptyHistory,
  emptyProfile,
  normalizeCover,
  signInEmail,
  purgeEntry,
  type Profile,
  type Identity,
  type Role,
  type Enrollment,
  type History,
  type Category,
  type Event,
  type Entry,
} from "../domain/DomainModels";
export const cloudMode = Boolean(
  import.meta.env.VITE_FIREBASE_API_KEY &&
  import.meta.env.VITE_FIREBASE_AUTH_DOMAIN &&
  import.meta.env.VITE_FIREBASE_PROJECT_ID &&
  import.meta.env.VITE_FIREBASE_STORAGE_BUCKET &&
  import.meta.env.VITE_FIREBASE_APP_ID,
);
type Cloud = typeof import("./FirebaseClient");
let cloud: Cloud | null = null;
// Opens Firebase once and waits until the saved sign-in session is ready.
export async function firebase() {
  cloud ??= await import("./FirebaseClient");
  await cloud.authReady;
  return cloud;
}
// Tells other signed-in devices to reload account files after a profile or history write.
export async function notifyAccountChanged(uid: string) {
  const { db } = await firebase();
  const { doc, setDoc, increment, serverTimestamp } = await import("firebase/firestore");
  await setDoc(
    doc(db, "accountSync", uid),
    { revision: increment(1), updatedAt: serverTimestamp() },
    { merge: true },
  );
}
// Watches a private, content-free marker so changes in Storage appear on other devices.
export async function observeAccountChanges(
  uid: string,
  onChange: () => void,
  onError: (error: Error) => void,
) {
  const { db } = await firebase();
  const { doc, onSnapshot } = await import("firebase/firestore");
  return onSnapshot(
    doc(db, "accountSync", uid),
    () => onChange(),
    (error) => onError(error),
  );
}
export class Accounts {
  identity: Identity | null = null;
  private raw: Uint8Array | null = null;
  private queue: Promise<unknown> = Promise.resolve();
  async authenticate(
    username: string,
    password: string,
    role: Role,
    create = false,
  ) {
    // Local demo accounts do not contact Firebase.
    if (!cloudMode) {
      const result = await (create ? createLocal : loginLocal)(
        username,
        password,
        role,
      );
      this.identity = result.identity;
      this.raw = result.raw;
    } else {
      const { auth, functions } = await firebase();
      const sdk = await import("firebase/auth");
      const { httpsCallable } = await import("firebase/functions");
      let user;
      // Firebase creates the sign-in account before Olive saves its profile.
      if (create) {
        if (role === "STAFF" || role === "ADMIN")
          throw new Error(
            "Organization accounts must be provisioned by an administrator.",
          );
        const normalized = username.trim().toLowerCase();
        await httpsCallable(
          functions,
          "createPublicAccount",
        )({
          username: normalized,
          password,
          role,
        });
        user = (
          await sdk.signInWithEmailAndPassword(
            auth,
            signInEmail(normalized, role),
            password,
          )
        ).user;
      } else {
        // A second lookup supports older accounts that saved the username in a different form.
        try {
          user = (
            await sdk.signInWithEmailAndPassword(
              auth,
              signInEmail(username, role),
              password,
            )
          ).user;
        } catch (error) {
          if (
            (role === "STAFF" || role === "ADMIN") &&
            username.includes("@")
          ) {
            const result = await httpsCallable(
              functions,
              "resolveOrganizationLogin",
            )({ email: username.trim().toLowerCase(), role });
            user = (
              await sdk.signInWithEmailAndPassword(
                auth,
                String((result.data as { authEmail: string }).authEmail),
                password,
              )
            ).user;
          } else if (role !== "USER")
            user = (
              await sdk.signInWithEmailAndPassword(
                auth,
                signInEmail(username, "USER"),
                password,
              )
            ).user;
          else throw error;
        }
        if (role === "STAFF" || role === "ADMIN") {
          await httpsCallable(functions, "restoreOrganizationLoginAlias")();
          await user.reload();
        }
      }
      const claims = await user.getIdTokenResult();
      if (
        (role === "ADMIN" || role === "STAFF") &&
        claims.claims.role !== role
      ) {
        await sdk.signOut(auth);
        throw new Error("This account does not have that organization role.");
      }
      this.identity = {
        uid: user.uid,
        username: user.displayName || user.email!.split("@")[0],
        role,
      };
      const db = await database;
      const saved = await db.get("accounts", `CLOUD:${user.uid}`);
      // A missing history file means this account has no saved activity yet.
      try {
        this.raw = saved
          ? await unwrapKey(password, saved.passwordKey)
          : crypto.getRandomValues(new Uint8Array(32));
      } catch {
        this.raw = crypto.getRandomValues(new Uint8Array(32));
        await db.delete("device", "current");
      }
      await db.put("accounts", {
        id: `CLOUD:${user.uid}`,
        passwordKey: await wrapKey(password, this.raw),
      });
    }
    return this.load();
  }
  async load(): Promise<{ profile: Profile | null; history: History }> {
    const { identity, raw } = this.active();
    // Local demo accounts read their profile from this browser.
    if (!cloudMode) {
      const value = await readLocal(identity.uid, raw);
      value.history.private_entries = value.history.private_entries.map((e) =>
        purgeEntry(e),
      );
      return value;
    }
    const { storage, auth } = await firebase();
    if (auth.currentUser?.uid !== identity.uid)
      throw new Error("Sign in to reconnect to your account.");
    const { ref, getBytes, listAll } = await import("firebase/storage");
    let profile: Profile | null = null;
    const history = emptyHistory();
    // Return an empty history when an older account has not created this file yet.
    try {
      const bytes = await getBytes(
        ref(storage, `accounts/${identity.uid}/profile.json`),
        65536,
      );
      const saved = JSON.parse(new TextDecoder().decode(bytes));
      profile = {
        ...emptyProfile(identity.username, identity.role),
        ...saved,
        coverType: normalizeCover(saved.coverType),
      };
      if (
        profile!.username !== identity.username ||
        profile!.role !== identity.role
      )
        throw new Error("Account profile does not match this sign-in.");
    } catch (error) {
      if ((error as { code?: string }).code !== "storage/object-not-found")
        throw error;
    }
    for (const category of Object.keys(history) as Category[]) {
      const listing = await listAll(
        ref(storage, `accounts/${identity.uid}/history/${category}`),
      );
      const rows = await Promise.all(
        listing.items.map(async (item) =>
          JSON.parse(
            new TextDecoder().decode(
              await getBytes(
                item,
                category === "private_entries" ? 5 * 1024 * 1024 : 65536,
              ),
            ),
          ),
        ),
      );
      if (category === "private_entries")
        history.private_entries = rows.map((row) => purgeEntry(row));
      else history[category] = rows;
    }
    await writeLocal(identity.uid, raw, { profile, history });
    return { profile, history };
  }
  private active() {
    if (!this.identity || !this.raw)
      throw new Error("Unlock your account first.");
    return { identity: this.identity, raw: this.raw.slice() };
  }
  async enroll(profile: Profile, pin: string) {
    const { identity, raw } = this.active();
    const enrollment: Enrollment = {
      uid: identity.uid,
      username: identity.username,
      cover: profile.coverType,
      role: profile.role,
      triggerHash: profile.triggerHash,
      pinKey: await wrapKey(pin, raw),
      failures: 0,
    };
    await (await database).put("device", enrollment, "current");
    return enrollment;
  }
  async unlock(pin: string) {
    const enrollment: Enrollment = await (
      await database
    ).get("device", "current");
    let raw: Uint8Array;
    // Older accounts may not have a browser-enrollment file yet.
    try {
      raw = await unwrapKey(pin, enrollment.pinKey);
    } catch {
      this.lock();
      const failures = (enrollment?.failures ?? 0) + 1;
      if (enrollment)
        await (
          await database
        ).put(
          "device",
          { ...enrollment, failures: failures >= 5 ? 0 : failures },
          "current",
        );
      throw new Error(
        failures >= 5
          ? "Incorrect PIN. Return to your cover and try again."
          : `Incorrect PIN. ${5 - failures} attempts remaining.`,
      );
    }
    this.raw = raw;
    this.identity = {
      uid: enrollment.uid,
      username: enrollment.username,
      role: enrollment.role,
    };
    // Older accounts may not have any failed-entry history yet.
    try {
      const value = await readLocal(enrollment.uid, raw);
      if (!value.profile) throw new Error("Sign in again to finish setup.");
      await (
        await database
      ).put("device", { ...enrollment, failures: 0 }, "current");
      if (cloudMode) return await this.load();
      return {
        ...value,
        history: {
          ...value.history,
          private_entries: value.history.private_entries.map((e) =>
            purgeEntry(e),
          ),
        },
      };
    } catch (error) {
      this.lock();
      throw error;
    }
  }
  lock() {
    this.raw?.fill(0);
    this.raw = null;
    this.identity = null;
  }
  async saveProfile(profile: Profile) {
    if (
      profile.username !== this.active().identity.username ||
      profile.role !== this.active().identity.role
    )
      throw new Error("Account mismatch.");
    return this.serialize(async () => {
      const { identity, raw } = this.active();
      // Cloud accounts also remove their Firebase sign-in record.
      if (cloudMode) {
        const { storage } = await firebase();
        const { ref, uploadBytes } = await import("firebase/storage");
        await uploadBytes(
          ref(storage, `accounts/${identity.uid}/profile.json`),
          new TextEncoder().encode(JSON.stringify(profile, null, 2)),
          {
            contentType: "application/json",
            customMetadata: { schemaVersion: "2", format: "pretty-json" },
          },
        );
        await notifyAccountChanged(identity.uid);
      }
      const cache = await readLocal(identity.uid, raw);
      await writeLocal(identity.uid, raw, { ...cache, profile });
      const enrollment: Enrollment | undefined = await (
        await database
      ).get("device", "current");
      if (enrollment?.uid === identity.uid)
        await (
          await database
        ).put(
          "device",
          {
            ...enrollment,
            cover: profile.coverType,
            triggerHash: profile.triggerHash,
          },
          "current",
        );
    });
  }
  async saveRecord(category: Category, record: Event | Entry) {
    return this.serialize(async () => {
      const { identity, raw } = this.active();
      // Cloud accounts register this browser so encrypted features can identify it.
      if (cloudMode) {
        const { storage } = await firebase();
        const { ref, uploadBytes } = await import("firebase/storage");
        await uploadBytes(
          ref(
            storage,
            `accounts/${identity.uid}/history/${category}/${record.id}.json`,
          ),
          new TextEncoder().encode(JSON.stringify(record, null, 2)),
          { contentType: "application/json" },
        );
        await notifyAccountChanged(identity.uid);
      }
      const cache = await readLocal(identity.uid, raw);
      const records = cache.history[category] as (Event | Entry)[];
      cache.history[category] = [
        record,
        ...records.filter((r) => r.id !== record.id),
      ] as Entry[] & Event[];
      await writeLocal(identity.uid, raw, cache);
    });
  }
  private serialize<T>(action: () => Promise<T>) {
    const pending = this.queue.catch(() => {}).then(action);
    this.queue = pending;
    return pending;
  }
  async changePassword(current: string, next: string) {
    const { identity } = this.active();
    if (!cloudMode) return changeLocalPassword(identity, current, next);
    const { auth } = await firebase();
    const { reauthenticateWithCredential, EmailAuthProvider, updatePassword } =
      await import("firebase/auth");
    if (!auth.currentUser) throw new Error("Sign in again first.");
    await reauthenticateWithCredential(
      auth.currentUser,
      EmailAuthProvider.credential(auth.currentUser.email!, current),
    );
    await updatePassword(auth.currentUser, next);
    await (
      await database
    ).put("accounts", {
      id: `CLOUD:${identity.uid}`,
      passwordKey: await wrapKey(next, this.active().raw),
    });
  }
  async changePin(current: string, next: string, profile: Profile) {
    const { identity, raw } = this.active();
    const saved: Enrollment | undefined = await (
      await database
    ).get("device", "current");
    if (!saved || saved.uid !== identity.uid)
      throw new Error("Enroll this browser first.");
    let candidate: Uint8Array;
    try {
      candidate = await unwrapKey(current, saved.pinKey);
    } catch {
      throw new Error("Current PIN is incorrect.");
    }
    let mismatch = raw.length ^ candidate.length;
    for (let i = 0; i < raw.length; i++) mismatch |= raw[i] ^ candidate[i];
    candidate.fill(0);
    raw.fill(0);
    if (mismatch) throw new Error("Current PIN is incorrect.");
    await this.enroll(profile, next);
  }
  async signOut() {
    this.lock();
    if (cloudMode) {
      const { auth } = await firebase();
      await (await import("firebase/auth")).signOut(auth);
    }
  }
}
export const accounts = new Accounts();
export { triggerHash };
