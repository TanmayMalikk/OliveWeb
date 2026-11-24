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
  unlock: vi.fn(),
  unlockKeyset: vi.fn(),
}));

vi.mock("./AccountService", () => ({
  firebase: async () => ({db: "db", functions: "functions"}),
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
  createOpaqueRecoveryFlow: () => ({unlock: mocks.unlock}),
}));
vi.mock("./AccountKeysetCrypto", async (importOriginal) => ({
  ...await importOriginal<typeof import("./AccountKeysetCrypto")>(),
  unlockAccountKeyset: mocks.unlockKeyset,
}));
vi.mock("firebase/firestore", () => ({doc: vi.fn(), getDoc: mocks.getDoc}));
vi.mock("firebase/functions", () => ({httpsCallable: () => mocks.callable}));

import { recoverBrowserAccountKeyset } from "./BrowserAccountKeysetRecovery";
import { accountKeysetSession } from "./AccountService";
import { accountKeysetWrappers } from "./AccountKeysetCrypto";

describe("browser account-keyset recovery", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.installed = null;
    mocks.callable.mockResolvedValue({data: {}});
    mocks.getDoc.mockResolvedValue({exists: () => true, data: () => ({accountId: "user-a"})});
    mocks.unlock.mockResolvedValue(new Uint8Array(32).fill(7));
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
});
