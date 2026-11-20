import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { importKey, toBase64 } from "./EncryptionService";
import { listBrowserDocuments, openBrowserDocument, uploadBrowserDocument } from "./MediaService";

const mocks = vi.hoisted(() => ({
  rows: vi.fn(),
  callable: vi.fn(),
  keyset: vi.fn(),
  getDoc: vi.fn(),
  getDocs: vi.fn(),
}));

vi.mock("./AccountService", () => ({
  firebase: async () => ({ db: {}, functions: {} }),
  accountKeysetSession: { current: (uid: string) => mocks.keyset(uid) },
}));
vi.mock("./MessagingService", () => ({
  messageDevice: async () => ({ deviceId: "new-browser" }),
  register: vi.fn(),
  unwrapRoomKeyBytes: vi.fn().mockRejectedValue(new Error("Wrapped for Android")),
}));
vi.mock("firebase/firestore", () => ({
  collection: (...parts: unknown[]) => parts.join("/"),
  doc: (...parts: unknown[]) => parts.join("/"),
  query: vi.fn(), where: vi.fn(),
  getDoc: (reference: unknown) => mocks.getDoc(reference),
  getDocs: (reference: unknown) => mocks.getDocs(reference),
}));
vi.mock("firebase/functions", () => ({
  httpsCallable: (_functions: unknown, name: string) => (data: unknown) => mocks.callable(name, data),
}));

beforeEach(() => {
  mocks.getDocs.mockImplementation(async () => ({ docs: mocks.rows() }));
});
afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

it("opens an Android upload using only a locally unlocked account keyset", async () => {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const keyId = "123e4567-e89b-42d3-a456-426614174000";
  const pair = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 3072, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    false,
    ["encrypt", "decrypt"],
  );
  const accountWrapper = toBase64(new Uint8Array(await crypto.subtle.encrypt(
    { name: "RSA-OAEP" }, pair.publicKey, raw,
  )));
  mocks.keyset.mockReturnValue({
    accountId: "user-a", keyId, epoch: 1, publicKey: pair.publicKey, privateKey: pair.privateKey,
  });
  const key = await importKey(raw);
  const encoder = new TextEncoder();
  async function encrypted(value: string, header: number[] = []) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const body = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoder.encode(value));
    return Uint8Array.from([...header, ...iv, ...new Uint8Array(body)]);
  }

  const ciphertext = await encrypted("Test document", [0x4f, 0x4c, 0x56, 0x4d, 1, 12]);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", ciphertext));
  const row = {
    owner_id: "user-a",
    object_key: "encrypted/file",
    encrypted_metadata: toBase64(await encrypted(JSON.stringify({ name: "phone.txt", mimeType: "text/plain" }))),
    ciphertext_size: ciphertext.length,
    ciphertext_sha256: [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join(""),
    wrapped_keys: { "user-a": "android-only-envelope" },
    account_wrapped_keys: { "user-a": { keyId, epoch: 1, algorithm: "RSA-OAEP-256", wrappedKey: accountWrapper } },
  };
  mocks.rows.mockReturnValue([
    { id: "phone-upload", data: () => row },
    { id: "older-upload", data: () => ({ ...row, account_wrapped_keys: {} }) },
  ]);
  mocks.callable.mockResolvedValue({ data: { url: "https://test.r2.cloudflarestorage.com/download" } });

  const [document, older] = await listBrowserDocuments({ uid: "user-a", username: "tester", role: "USER" });
  expect(document).toMatchObject({ approved: true, name: "phone.txt", mimeType: "text/plain" });
  expect(older.approved).toBe(false);
  expect(mocks.callable).not.toHaveBeenCalled();

  const fetchBlob = globalThis.fetch;
  vi.stubGlobal("fetch", (url: string) =>
    url.startsWith("blob:") ? fetchBlob(url) : Promise.resolve(new Response(ciphertext)));
  const url = await openBrowserDocument(document);
  try {
    expect(await (await fetch(url)).text()).toBe("Test document");
    expect(mocks.callable).toHaveBeenCalledExactlyOnceWith("mediaDownloadTicket", { mediaId: "phone-upload" });
  } finally {
    URL.revokeObjectURL(url);
  }
});

it("uploads only ciphertext and opaque media-key wrappers", async () => {
  const identity = { uid: "user-a", username: "tester", role: "USER" as const };
  const keyId = "123e4567-e89b-42d3-a456-426614174000";
  const pair = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 3072, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const publicKey = toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
  mocks.getDoc.mockImplementation(async (reference: string) => reference.includes("accountKeyDirectories")
    ? { get: (field: string) => ({ ownerUid: identity.uid, keyId, epoch: 1, algorithm: "RSA-OAEP-256", publicKey })[field] }
    : { get: (field: string) => ({ uid: identity.uid, publicKey })[field] });
  mocks.getDocs.mockResolvedValue({ docs: [{ data: () => ({
    uid: identity.uid, deviceId: "web-device", publicKey, mgf1Digest: "SHA-256",
  }) }] });
  mocks.callable.mockImplementation(async (name: string) => {
    if (name === "mediaCreateUpload") {
      return { data: { mediaId: "browser-upload", mode: "SINGLE", partSize: 0 } };
    }
    if (name === "mediaUploadPartTicket") {
      return { data: { url: "https://test.r2.cloudflarestorage.com/upload" } };
    }
    return { data: {} };
  });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));

  await expect(uploadBrowserDocument(
    new File([new Uint8Array([1, 2, 3])], "private.txt", { type: "text/plain" }),
    identity,
  )).resolves.toBe("browser-upload");

  const create = mocks.callable.mock.calls.find(([name]) => name === "mediaCreateUpload")?.[1] as Record<string, unknown>;
  expect(create).not.toHaveProperty("recoveryKeyToSeal");
  expect(create).not.toHaveProperty("accountAccessKey");
  expect(create).toMatchObject({ allowedUsers: [identity.uid], encryptionVersion: 2 });
  expect(create.accountWrappedKeys).toMatchObject({
    [identity.uid]: { keyId, epoch: 1, algorithm: "RSA-OAEP-256" },
  });
  expect(JSON.stringify(create)).not.toContain("private.txt");
});
