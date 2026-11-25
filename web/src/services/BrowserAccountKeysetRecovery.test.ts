import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  install: vi.fn(),
  installed: null as null | {
    accountId: string;
    keyId: string;
    epoch: number;
    publicKey: CryptoKey;
    privateKey: CryptoKey;
  },
  callable: vi.fn(),
  getDoc: vi.fn(),
  doc: vi.fn((_db: unknown, collection: string, id: string) => `${collection}/${id}`),
  batchSet: vi.fn(),
  batchCommit: vi.fn(),
  serverTimestamp: vi.fn(() => "SERVER_TS"),
  reauth: vi.fn(),
  getIdToken: vi.fn(),
  credential: vi.fn((email: string, password: string) => ({ email, password })),
  currentUser: { uid: "user-a", email: "user@example.com", getIdToken: vi.fn() } as null | {
    uid: string;
    email: string | null;
    getIdToken: (forceRefresh?: boolean) => Promise<string>;
  },
  unlock: vi.fn(),
  register: vi.fn(),
  rotate: vi.fn(),
  unlockKeyset: vi.fn(),
}));

vi.mock("./AccountService", () => ({
  firebase: async () => ({ auth: { currentUser: mocks.currentUser }, db: "db", functions: "functions" }),
  accountKeysetSession: {
    install: (keyset: typeof mocks.installed) => {
      mocks.installed = keyset;
      mocks.install(keyset);
    },
    current: (uid: string) => mocks.installed?.accountId === uid ? mocks.installed : null,
    clear: () => { mocks.installed = null; },
  },
}));
vi.mock("./OpaqueRecoveryFlow", () => ({
  createOpaqueRecoveryFlow: () => ({
    unlock: mocks.unlock,
    register: mocks.register,
    rotate: mocks.rotate,
  }),
}));
vi.mock("./AccountKeysetCrypto", async (importOriginal) => ({
  ...await importOriginal<typeof import("./AccountKeysetCrypto")>(),
  unlockAccountKeyset: mocks.unlockKeyset,
}));
vi.mock("firebase/firestore", () => ({
  doc: mocks.doc,
  getDoc: mocks.getDoc,
  serverTimestamp: mocks.serverTimestamp,
  writeBatch: () => ({
    set: mocks.batchSet,
    commit: mocks.batchCommit,
  }),
}));
vi.mock("firebase/auth", () => ({
  EmailAuthProvider: { credential: mocks.credential },
  reauthenticateWithCredential: mocks.reauth,
}));
vi.mock("firebase/functions", () => ({httpsCallable: () => mocks.callable}));

import {
  enrollBrowserAccountRecovery,
  getBrowserPasswordRecoveryStatus,
  recoverBrowserAccountKeyset,
  rotateBrowserPasswordRecovery,
} from "./BrowserAccountKeysetRecovery";
import { accountKeysetSession } from "./AccountService";
import {
  accountKeysetWrappers,
  createAccountKeysetEnvelope,
} from "./AccountKeysetCrypto";

describe("browser account-keyset recovery", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.installed = null;
    mocks.currentUser = {
      uid: "user-a",
      email: "user@example.com",
      getIdToken: mocks.getIdToken.mockResolvedValue("token"),
    };
    mocks.callable.mockResolvedValue({data: {}});
    mocks.batchCommit.mockResolvedValue(undefined);
    mocks.reauth.mockResolvedValue(undefined);
    mocks.getDoc.mockResolvedValue({exists: () => true, data: () => ({accountId: "user-a"})});
    mocks.unlock.mockResolvedValue(new Uint8Array(32).fill(7));
    mocks.register.mockResolvedValue({version: 1});
    mocks.rotate.mockResolvedValue({version: 1});
    mocks.unlockKeyset.mockResolvedValue({accountId: "user-a", privateKey: "private"});
  });

  it("unlocks a fetched ciphertext envelope with OPAQUE password recovery and clears the key", async () => {
    const key = new Uint8Array(32).fill(7);
    mocks.unlock.mockResolvedValue(key);

    await recoverBrowserAccountKeyset("user-a", "RecoveryPassword123!");

    expect(mocks.unlock).toHaveBeenCalledWith("user-a", "RecoveryPassword123!");
    expect(mocks.unlockKeyset).toHaveBeenCalledWith("user-a", key, {accountId: "user-a"});
    expect(mocks.install).toHaveBeenCalledWith({accountId: "user-a", privateKey: "private"});
    expect([...key]).toEqual(Array(32).fill(0));
  });

  it("does not invoke OPAQUE unlock when the encrypted keyset is absent", async () => {
    mocks.getDoc.mockResolvedValue({exists: () => false});

    await expect(recoverBrowserAccountKeyset("user-a", "RecoveryPassword123!")).rejects.toThrow("has not been set up");
    expect(mocks.unlock).not.toHaveBeenCalled();
  });

  it("restores fresh-browser access to existing document and chat ciphertext", async () => {
    const uid = "user-a";
    const keyId = "123e4567-e89b-12d3-a456-426614174000";
    const pair = await crypto.subtle.generateKey(
      {name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256"},
      true,
      ["encrypt", "decrypt"],
    );
    const publicKey = btoa(String.fromCharCode(
      ...new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)),
    ));
    const directory = {ownerUid: uid, keyId, epoch: 1, algorithm: "RSA-OAEP-256", publicKey} as const;
    const documentKey = crypto.getRandomValues(new Uint8Array(32));
    const roomKey = crypto.getRandomValues(new Uint8Array(32));
    const documentWrapper = await accountKeysetWrappers.wrap(uid, documentKey, directory);
    const roomWrapper = await accountKeysetWrappers.wrap(uid, roomKey, directory);
    mocks.unlockKeyset.mockResolvedValue({
      accountId: uid,
      keyId,
      epoch: 1,
      publicKey: pair.publicKey,
      privateKey: pair.privateKey,
    });

    expect(accountKeysetSession.current(uid)).toBeNull();
    await recoverBrowserAccountKeyset(uid, "RecoveryPassword123!");

    const installed = accountKeysetSession.current(uid);
    expect(accountKeysetSession.current("other-user")).toBeNull();
    const recoveredDocumentKey = await accountKeysetWrappers.unwrap(uid, documentWrapper, installed);
    const recoveredRoomKey = await accountKeysetWrappers.unwrap(uid, roomWrapper, installed);
    expect(recoveredDocumentKey).toEqual(documentKey);
    expect(recoveredRoomKey).toEqual(roomKey);

    const encoder = new TextEncoder();
    const documentIv = crypto.getRandomValues(new Uint8Array(12));
    const messageIv = crypto.getRandomValues(new Uint8Array(12));
    const documentCiphertext = await crypto.subtle.encrypt(
      {name: "AES-GCM", iv: documentIv},
      await crypto.subtle.importKey("raw", documentKey, "AES-GCM", false, ["encrypt"]),
      encoder.encode("existing document"),
    );
    const messageCiphertext = await crypto.subtle.encrypt(
      {name: "AES-GCM", iv: messageIv},
      await crypto.subtle.importKey("raw", roomKey, "AES-GCM", false, ["encrypt"]),
      encoder.encode("existing chat message"),
    );
    await expect(crypto.subtle.decrypt(
      {name: "AES-GCM", iv: documentIv},
      await crypto.subtle.importKey("raw", recoveredDocumentKey! as BufferSource, "AES-GCM", false, ["decrypt"]),
      documentCiphertext,
    )).resolves.toEqual(encoder.encode("existing document").buffer);
    await expect(crypto.subtle.decrypt(
      {name: "AES-GCM", iv: messageIv},
      await crypto.subtle.importKey("raw", recoveredRoomKey! as BufferSource, "AES-GCM", false, ["decrypt"]),
      messageCiphertext,
    )).resolves.toEqual(encoder.encode("existing chat message").buffer);
    recoveredDocumentKey?.fill(0);
    recoveredRoomKey?.fill(0);
  });

  it("creates Firestore-compatible 3072-bit account keyset envelopes and unwraps content keys", async () => {
    const uid = "staff-uid-1";
    const ark = crypto.getRandomValues(new Uint8Array(32));
    const { envelope, keyset } = await createAccountKeysetEnvelope(uid, ark);

    expect(envelope.version).toBe(1);
    expect(envelope.accountId).toBe(uid);
    expect(envelope.epoch).toBe(1);
    expect(envelope.algorithm).toBe("RSA-OAEP-256");
    expect(envelope.publicKey.length).toBeGreaterThanOrEqual(400);
    expect(envelope.publicKey.length).toBeLessThanOrEqual(1024);
    expect(envelope.iv.length).toBe(16);
    expect(envelope.ciphertext.length).toBeGreaterThanOrEqual(2000);
    expect(envelope.ciphertext.length).toBeLessThanOrEqual(4096);
    expect(keyset.privateKey.extractable).toBe(false);

    const { unlockAccountKeyset: realUnlockAccountKeyset } = await vi.importActual<
      typeof import("./AccountKeysetCrypto")
    >("./AccountKeysetCrypto");
    const unlocked = await realUnlockAccountKeyset(uid, ark, envelope);
    const contentKey = crypto.getRandomValues(new Uint8Array(32));
    const wrapped = await accountKeysetWrappers.wrap(uid, contentKey, {
      ownerUid: uid,
      keyId: envelope.keyId,
      epoch: envelope.epoch,
      algorithm: envelope.algorithm,
      publicKey: envelope.publicKey,
    });
    const unwrappedFromCreated = await accountKeysetWrappers.unwrap(uid, wrapped, keyset);
    const unwrappedFromRestored = await accountKeysetWrappers.unwrap(uid, wrapped, unlocked);
    expect(unwrappedFromCreated).toEqual(contentKey);
    expect(unwrappedFromRestored).toEqual(contentKey);
    ark.fill(0);
    unwrappedFromCreated?.fill(0);
    unwrappedFromRestored?.fill(0);
  });

  it("enrolls ARK, writes Firestore directory and keyset batch, registers OPAQUE, and installs session keyset", async () => {
    mocks.getDoc.mockResolvedValue({ exists: () => false });

    await enrollBrowserAccountRecovery("user-a", "AccountPassword1!");

    expect(mocks.reauth).toHaveBeenCalledTimes(1);
    expect(mocks.getIdToken).toHaveBeenCalledWith(true);
    expect(mocks.batchSet).toHaveBeenCalledTimes(2);
    expect(mocks.batchSet.mock.calls[0][0]).toBe("accountKeysets/user-a");
    expect(mocks.batchSet.mock.calls[1][0]).toBe("accountKeyDirectories/user-a");
    expect(mocks.batchCommit).toHaveBeenCalledTimes(1);
    expect(mocks.register).toHaveBeenCalledTimes(1);
    const registeredKey = mocks.register.mock.calls[0][2] as Uint8Array;
    expect([...registeredKey]).toEqual(Array(32).fill(0));
    expect(accountKeysetSession.current("user-a")?.accountId).toBe("user-a");
  });

  it("refuses to overwrite an existing keyset during enrollment", async () => {
    mocks.getDoc.mockResolvedValue({ exists: () => true, data: () => ({ accountId: "user-a" }) });
    await expect(enrollBrowserAccountRecovery("user-a", "AccountPassword1!"))
      .rejects.toThrow("already exists on another device");
    expect(mocks.batchCommit).not.toHaveBeenCalled();

    mocks.getDoc.mockResolvedValue({
      exists: () => true,
      data: () => ({ accountId: "user-a", passwordRecovery: { version: 1 } }),
    });
    await expect(enrollBrowserAccountRecovery("user-a", "AccountPassword1!"))
      .rejects.toThrow("already enabled");
  });

  it("reports password recovery status and rotates enrolled OPAQUE records", async () => {
    mocks.getDoc.mockResolvedValueOnce({ exists: () => false });
    await expect(getBrowserPasswordRecoveryStatus("user-a")).resolves.toEqual({
      hasKeyset: false,
      enrolled: false,
    });

    mocks.getDoc.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ accountId: "user-a", passwordRecovery: { version: 1 } }),
    });
    await expect(getBrowserPasswordRecoveryStatus("user-a")).resolves.toEqual({
      hasKeyset: true,
      enrolled: true,
    });

    const recoveredArk = new Uint8Array(32).fill(5);
    mocks.unlock.mockResolvedValueOnce(recoveredArk);
    mocks.getDoc.mockResolvedValueOnce({
      exists: () => true,
      data: () => ({ accountId: "user-a", passwordRecovery: { version: 1 } }),
    });
    await rotateBrowserPasswordRecovery("user-a", "OldPass1!", "NewPass2!");
    expect(mocks.credential).toHaveBeenCalledWith("user@example.com", "NewPass2!");
    expect(mocks.unlock).toHaveBeenCalledWith("user-a", "OldPass1!");
    expect(mocks.rotate).toHaveBeenCalledTimes(1);
    expect([...recoveredArk]).toEqual(Array(32).fill(0));
    expect(mocks.install).toHaveBeenCalled();
  });
});
