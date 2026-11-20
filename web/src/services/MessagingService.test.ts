import { beforeEach, expect, it, vi } from "vitest";
import { accountKeysetWrappers } from "./PasskeyRecovery";
import { roomKey } from "./MessagingService";

const mocks = vi.hoisted(() => ({
  getDoc: vi.fn(),
  getDocs: vi.fn(),
  setDoc: vi.fn(),
  keyset: vi.fn(),
  device: new Map<string, unknown>(),
}));

vi.mock("./AccountService", () => ({
  firebase: async () => ({ db: {}, functions: {} }),
  accountKeysetSession: { current: (uid: string) => mocks.keyset(uid) },
}));
vi.mock("./LocalDataStore", () => ({
  database: Promise.resolve({
    get: async (_store: string, key: string) => mocks.device.get(key),
    put: async (_store: string, value: unknown, key: string) => {
      mocks.device.set(key, value);
    },
  }),
}));
vi.mock("firebase/firestore", () => ({
  collection: (...parts: unknown[]) => parts.join("/"),
  doc: (...parts: unknown[]) => parts.join("/"),
  getDoc: (reference: unknown) => mocks.getDoc(reference),
  getDocs: (reference: unknown) => mocks.getDocs(reference),
  setDoc: (...args: unknown[]) => mocks.setDoc(...args),
  deleteDoc: vi.fn(),
  onSnapshot: vi.fn(),
  query: (value: unknown) => value,
  where: vi.fn(),
  orderBy: vi.fn(),
  limitToLast: vi.fn(),
  limit: vi.fn(),
  serverTimestamp: () => "server-time",
  runTransaction: vi.fn(),
  writeBatch: () => ({ set: vi.fn(), update: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }),
}));

beforeEach(() => {
  mocks.device.clear();
  vi.clearAllMocks();
  mocks.getDocs.mockResolvedValue({ docs: [], empty: true });
  mocks.setDoc.mockResolvedValue(undefined);
});

it("recovers a chat room key from the locally unlocked account keyset", async () => {
  const uid = "account-a";
  const keyId = "123e4567-e89b-12d3-a456-426614174000";
  const raw = new Uint8Array(32).fill(29);
  const pair = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const publicKey = btoa(String.fromCharCode(
    ...new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)),
  ));
  const wrapper = await accountKeysetWrappers.wrap(uid, raw, {
    ownerUid: uid,
    keyId,
    epoch: 1,
    algorithm: "RSA-OAEP-256",
    publicKey,
  });
  mocks.keyset.mockReturnValue({
    accountId: uid,
    keyId,
    epoch: 1,
    publicKey: pair.publicKey,
    privateKey: pair.privateKey,
  });
  mocks.getDoc.mockImplementation(async (reference: string) => {
    if (reference === "[object Object]/messageDirectory/tester") {
      return { exists: () => true, data: () => ({ uid }) };
    }
    if (reference.includes("/deviceKeys/")) return { exists: () => false };
    if (reference === "[object Object]/conversations/chat-a") {
      return {
        exists: () => true,
        data: () => ({ participantIds: [uid], accountRoomKeys: { [uid]: wrapper } }),
        get: (field: string) => field === "participantIds" ? [uid] : undefined,
      };
    }
    return { exists: () => false, data: () => ({}) };
  });
  mocks.getDocs.mockRejectedValueOnce(new Error("Device directory unavailable"));

  const key = await roomKey("chat-a", { uid, username: "tester", role: "USER" }, {
    id: "chat-a",
    friendshipId: "chat-a",
    participantIds: [uid],
    participantUsernames: { [uid]: "tester" },
    lastMessage: "",
    updatedAt: 0,
    type: "DIRECT",
    ownerUid: uid,
    staffUid: "",
    adminUid: "",
    supportOrigin: "",
    supportTag: "",
    closedAt: 0,
  });
  const iv = new Uint8Array(12).fill(3);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv }, key, new TextEncoder().encode("recovered"),
  );
  const expected = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);
  await expect(crypto.subtle.decrypt({ name: "AES-GCM", iv }, expected, ciphertext))
    .resolves.toEqual(new TextEncoder().encode("recovered").buffer);
});
