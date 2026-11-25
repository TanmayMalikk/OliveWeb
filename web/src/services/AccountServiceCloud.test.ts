import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyProfile, type Entry, type Event, type Profile } from "../domain/DomainModels";
import type { UnlockedAccountKeyset } from "./AccountKeysetCrypto";

const mocks = vi.hoisted(() => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  vi.stubEnv("VITE_FIREBASE_AUTH_DOMAIN", "test.firebaseapp.com");
  vi.stubEnv("VITE_FIREBASE_PROJECT_ID", "olive-test");
  vi.stubEnv("VITE_FIREBASE_STORAGE_BUCKET", "olive-test.appspot.com");
  vi.stubEnv("VITE_FIREBASE_APP_ID", "1:123:web:abc");

  const deviceStore = new Map<string, unknown>();
  const accountStore = new Map<string, unknown>();
  const storageObjects = new Map<string, Uint8Array>();

  return {
    deviceStore,
    accountStore,
    storageObjects,
    localCache: new Map<string, { profile: Profile | null; history: { settings: Event[]; triggers: Event[]; private_entries: Entry[] } }>(),
    currentUser: null as {
      uid: string;
      displayName: string | null;
      email: string | null;
      getIdTokenResult: ReturnType<typeof vi.fn>;
      reload: ReturnType<typeof vi.fn>;
    } | null,
    signInWithEmailAndPassword: vi.fn(async (..._args: unknown[]) => ({ user: mocks.currentUser })),
    signOut: vi.fn(async (..._args: unknown[]) => {}),
    reauthenticateWithCredential: vi.fn(async (..._args: unknown[]) => {}),
    updatePassword: vi.fn(async (..._args: unknown[]) => {}),
    httpsCallable: vi.fn((..._args: unknown[]) => async (..._a: unknown[]) => ({ data: {} })),
    rotateRecovery: vi.fn(async () => {}),
    onSnapshot: vi.fn((..._args: unknown[]) => () => {}),
    setDoc: vi.fn(async (..._args: unknown[]) => {}),
    getDocFromServer: vi.fn(async (..._args: unknown[]): Promise<{ exists: () => boolean; data?: () => unknown }> => ({ exists: () => false })),
    unwrapRoomKeyBytes: vi.fn(async (..._args: unknown[]): Promise<Uint8Array | null> => null),
    parseWrapper: vi.fn(() => null as unknown),
    unwrapWrapper: vi.fn(async (..._args: unknown[]): Promise<Uint8Array | null> => null),
  };
});

vi.mock("./LocalDataStore", () => ({
  createLocal: vi.fn(),
  loginLocal: vi.fn(),
  changeLocalPassword: vi.fn(),
  readLocal: vi.fn(async (uid: string) => {
    return (
      mocks.localCache.get(uid) ?? {
        profile: null,
        history: { settings: [], triggers: [], private_entries: [] },
      }
    );
  }),
  writeLocal: vi.fn(async (uid: string, _raw: Uint8Array, data: { profile: Profile | null; history: { settings: Event[]; triggers: Event[]; private_entries: Entry[] } }) => {
    mocks.localCache.set(uid, data);
  }),
  database: Promise.resolve({
    get: vi.fn(async (store: string, key: string) =>
      store === "device" ? mocks.deviceStore.get(key) : mocks.accountStore.get(key),
    ),
    put: vi.fn(async (store: string, val: unknown, key?: string) => {
      if (store === "device") mocks.deviceStore.set(String(key), val);
      else mocks.accountStore.set((val as { id: string }).id, val);
    }),
    delete: vi.fn(async (store: string, key: string) => {
      if (store === "device") mocks.deviceStore.delete(key);
      else mocks.accountStore.delete(key);
    }),
  }),
}));

vi.mock("./FirebaseClient", () => ({
  authReady: Promise.resolve(),
  auth: {
    get currentUser() {
      return mocks.currentUser;
    },
  },
  db: {},
  storage: {},
  functions: {},
}));

vi.mock("firebase/auth", () => ({
  signInWithEmailAndPassword: (...args: unknown[]) => mocks.signInWithEmailAndPassword(...args),
  signOut: (...args: unknown[]) => mocks.signOut(...args),
  reauthenticateWithCredential: (...args: unknown[]) => mocks.reauthenticateWithCredential(...args),
  updatePassword: (...args: unknown[]) => mocks.updatePassword(...args),
  EmailAuthProvider: { credential: (email: string, pass: string) => ({ email, pass }) },
}));

vi.mock("firebase/functions", () => ({
  httpsCallable: (...args: unknown[]) => mocks.httpsCallable(...args),
}));

vi.mock("firebase/firestore", () => ({
  doc: (_db: unknown, col: string, id: string) => ({ path: `${col}/${id}` }),
  setDoc: (...args: unknown[]) => mocks.setDoc(...args),
  increment: (n: number) => ({ inc: n }),
  serverTimestamp: () => "ts",
  onSnapshot: (...args: unknown[]) => mocks.onSnapshot(...args),
  getDocFromServer: (...args: unknown[]) => mocks.getDocFromServer(...args),
}));

vi.mock("firebase/storage", () => ({
  ref: (_storage: unknown, path: string) => ({ fullPath: path }),
  getBytes: vi.fn(async (reference: { fullPath: string }) => {
    const found = mocks.storageObjects.get(reference.fullPath);
    if (!found) throw Object.assign(new Error("Missing"), { code: "storage/object-not-found" });
    return found;
  }),
  uploadBytes: vi.fn(async (reference: { fullPath: string }, bytes: Uint8Array) => {
    mocks.storageObjects.set(reference.fullPath, bytes);
  }),
  listAll: vi.fn(async (reference: { fullPath: string }) => {
    const prefix = reference.fullPath + "/";
    const items = [...mocks.storageObjects.keys()]
      .filter((k) => k.startsWith(prefix))
      .map((fullPath) => ({ fullPath }));
    return { items };
  }),
}));

vi.mock("./BrowserAccountKeysetRecovery", () => ({
  rotateBrowserPasswordRecovery: () => mocks.rotateRecovery(),
}));

vi.mock("./MessagingService", () => ({
  messageDevice: vi.fn(async () => ({ deviceId: "dev-1" })),
  unwrapRoomKeyBytes: (...args: unknown[]) => mocks.unwrapRoomKeyBytes(...args),
}));

vi.mock("./AccountKeysetCrypto", () => ({
  accountKeysetWrappers: {
    parse: () => mocks.parseWrapper(),
    unwrap: (...args: unknown[]) => mocks.unwrapWrapper(...args),
  },
}));

import {
  Accounts,
  accountKeysetSession,
  cloudMode,
  notifyAccountChanged,
  observeAccountChanges,
} from "./AccountService";
import { toBase64 } from "./EncryptionService";
import { loadSharedPrivateEntry } from "./SharedPrivateEntryService";

describe("AccountService cloud flows and SharedPrivateEntryService", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.deviceStore.clear();
    mocks.accountStore.clear();
    mocks.storageObjects.clear();
    mocks.localCache.clear();
    accountKeysetSession.clear();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("verifies cloudMode, authenticate (create + staff alias fallback), saveProfile, saveRecord, enroll, unlock, password/pin change, and signOut", async () => {
    expect(cloudMode).toBe(true);
    const svc = new Accounts();

    const makeUser = (uid: string, username: string, role: string) => ({
      uid,
      displayName: username,
      email: `${username}@yourolivebranch.invalid`,
      getIdTokenResult: vi.fn(async () => ({ claims: { role } })),
      reload: vi.fn(async () => {}),
    });

    // Public create account + sign-in + load empty profile/history.
    mocks.httpsCallable.mockImplementation((_f, name) => {
      if (name === "resolveOrganizationLogin") return async () => ({ data: { authEmail: "staff@org.ca" } });
      return async () => ({ data: {} });
    });
    const user1 = makeUser("uid-1", "user_one", "USER");
    mocks.currentUser = user1;
    mocks.signInWithEmailAndPassword.mockResolvedValue({ user: user1 });

    const created = await svc.authenticate("user_one", "Pass123!", "USER", true);
    expect(created.profile).toBeNull();
    expect(mocks.setDoc).toHaveBeenCalledTimes(0);

    // Save profile, save record, reload from storage.
    const profile: Profile = {
      ...emptyProfile("user_one", "USER"),
      fullName: "Alex Rivera",
      dob: "1995-04-12",
      triggerHash: "trig-hash",
    };
    const savedProfile = await svc.saveProfile(profile);
    expect(savedProfile.fullName).toBe("Alex Rivera");
    expect(mocks.setDoc).toHaveBeenCalled();

    await svc.saveRecord("settings", { id: "ev-1", timestamp: 100, action: "Updated" });
    await svc.saveRecord("private_entries", {
      id: "note-1",
      timestamp: 100,
      updatedAt: 100,
      kind: "Note",
      title: "T",
      body: "B",
      pinned: false,
      edits: [],
    });

    const reloaded = await svc.load();
    expect(reloaded.profile?.fullName).toBe("Alex Rivera");
    expect(reloaded.history.private_entries).toHaveLength(1);

    // Enroll + wrong PIN + lock/unlock + changePassword + changePin + signOut.
    await svc.enroll(profile, "123456");
    await expect(svc.unlock("999999")).rejects.toThrow(/Incorrect PIN/i);
    const unlocked = await svc.unlock("123456");
    expect(unlocked.profile?.username).toBe("user_one");

    await svc.changePassword("Pass123!", "NewPass1!");
    expect(mocks.updatePassword).toHaveBeenCalled();
    await expect(svc.changePin("999999", "654321", profile)).rejects.toThrow(/incorrect/i);
    await svc.changePin("123456", "654321", profile);

    await svc.signOut();
    expect(mocks.signOut).toHaveBeenCalled();

    // Staff alias resolution fallback and role mismatch rejection.
    const staffUser = makeUser("staff-1", "staff_one", "STAFF");
    mocks.currentUser = staffUser;
    mocks.signInWithEmailAndPassword
      .mockRejectedValueOnce(new Error("user-not-found"))
      .mockResolvedValueOnce({ user: staffUser });
    await expect(svc.authenticate("staff_one", "Pass123!", "STAFF", false)).resolves.toBeTruthy();

    await expect(svc.authenticate("staff_one", "Pass123!", "ADMIN", true)).rejects.toThrow(/administrator/i);
    mocks.signInWithEmailAndPassword.mockResolvedValueOnce({ user: makeUser("u2", "badrole", "USER") });
    await expect(svc.authenticate("badrole", "Pass123!", "STAFF", false)).rejects.toThrow(/organization role/i);

    // observeAccountChanges and notifyAccountChanged.
    mocks.onSnapshot.mockImplementation((_ref, onNext) => {
      (onNext as () => void)();
      return () => {};
    });
    const onChange = vi.fn();
    await observeAccountChanges("uid-1", onChange, vi.fn());
    expect(onChange).toHaveBeenCalled();
    await notifyAccountChanged("uid-1");
  });

  it("decrypts shared private entries via device-wrapped and account-keyset keys", async () => {
    const identity = { uid: "uid-2", username: "friend", role: "SAFE_CONTACT" as const };
    expect(await loadSharedPrivateEntry("  ", identity)).toBeNull();

    // Missing doc returns null.
    mocks.getDocFromServer.mockResolvedValueOnce({ exists: () => false });
    expect(await loadSharedPrivateEntry("e1", identity)).toBeNull();

    // Encrypt real entry JSON with AES-GCM.
    const rawKey = crypto.getRandomValues(new Uint8Array(32));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const cryptoKey = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["encrypt", "decrypt"]);
    const plain = new TextEncoder().encode(
      JSON.stringify({
        id: "e-shared",
        kind: "Incident",
        title: "Shared Incident",
        body: "Detail",
        attachmentIds: ["doc-1", "doc-1", ""],
        pinned: true,
        timestamp: 500,
        updatedAt: 600,
      }),
    );
    const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, tagLength: 128 }, cryptoKey, plain));
    const combined = new Uint8Array(iv.length + cipher.length);
    combined.set(iv, 0);
    combined.set(cipher, iv.length);

    mocks.getDocFromServer.mockResolvedValue({
      exists: () => true,
      data: () => ({
        ciphertext: toBase64(combined),
        deviceWrappedKeys: { "uid-2--dev-1": "wrapped-1" },
      }),
    });
    mocks.unwrapRoomKeyBytes.mockResolvedValueOnce(rawKey.slice());

    const loaded = await loadSharedPrivateEntry("e-shared", identity);
    expect(loaded?.title).toBe("Shared Incident");
    expect(loaded?.attachmentIds).toEqual(["doc-1"]);
    expect(loaded?.kind).toBe("Incident");

    // Account-keyset wrapper fallback branch.
    accountKeysetSession.install({ accountId: "uid-2" } as UnlockedAccountKeyset);
    mocks.unwrapRoomKeyBytes.mockResolvedValueOnce(null);
    mocks.parseWrapper.mockReturnValueOnce({ wrapped: true });
    mocks.unwrapWrapper.mockResolvedValueOnce(rawKey.slice());
    const fromKeyset = await loadSharedPrivateEntry("e-shared", identity);
    expect(fromKeyset?.pinned).toBe(true);
  });
});
