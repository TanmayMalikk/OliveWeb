import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  install: vi.fn(),
  callable: vi.fn(),
  getDoc: vi.fn(),
  unlock: vi.fn(),
  unlockKeyset: vi.fn(),
}));

vi.mock("./AccountService", () => ({
  firebase: async () => ({db: "db", functions: "functions"}),
  accountKeysetSession: {install: mocks.install},
}));
vi.mock("./PasskeyWebAuthn", () => ({browserPasskeys: {register: vi.fn(), authenticate: vi.fn()}}));
vi.mock("./PasskeyRecoveryFlow", () => ({
  createPasskeyRecoveryFlow: () => ({unlock: mocks.unlock}),
}));
vi.mock("./PasskeyRecovery", () => ({unlockAccountKeyset: mocks.unlockKeyset}));
vi.mock("firebase/firestore", () => ({doc: vi.fn(), getDoc: mocks.getDoc}));
vi.mock("firebase/functions", () => ({httpsCallable: () => mocks.callable}));

import { recoverBrowserAccountKeyset } from "./BrowserAccountKeysetRecovery";

describe("browser account-keyset recovery", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.callable.mockResolvedValue({data: {}});
    mocks.getDoc.mockResolvedValue({exists: () => true, data: () => ({accountId: "user-a"})});
    mocks.unlock.mockResolvedValue(new Uint8Array(32).fill(7));
    mocks.unlockKeyset.mockResolvedValue({accountId: "user-a", privateKey: "private"});
  });

  it("unlocks a fetched ciphertext envelope only in the current tab and clears the recovery key", async () => {
    const key = new Uint8Array(32).fill(7);
    mocks.unlock.mockResolvedValue(key);

    await recoverBrowserAccountKeyset("user-a");

    expect(mocks.unlockKeyset).toHaveBeenCalledWith("user-a", key, {accountId: "user-a"});
    expect(mocks.install).toHaveBeenCalledWith({accountId: "user-a", privateKey: "private"});
    expect([...key]).toEqual(Array(32).fill(0));
  });

  it("does not prompt for a passkey when the encrypted keyset is absent", async () => {
    mocks.getDoc.mockResolvedValue({exists: () => false});

    await expect(recoverBrowserAccountKeyset("user-a")).rejects.toThrow("has not been set up");
    expect(mocks.unlock).not.toHaveBeenCalled();
  });
});
