import { afterEach, expect, it, vi } from "vitest";
import { importKey, toBase64 } from "./EncryptionService";
import { listBrowserDocuments, openBrowserDocument, uploadBrowserDocument, type BrowserDocument } from "./MediaService";

const mocks = vi.hoisted(() => ({
  rows: vi.fn(),
  callable: vi.fn(),
  directory: vi.fn(),
}));

vi.mock("./AccountService", () => ({ firebase: async () => ({ db: {}, functions: {} }) }));
vi.mock("./MessagingService", () => ({
  messageDevice: async () => ({ deviceId: "new-browser" }),
  register: vi.fn(),
  unwrapRoomKeyBytes: vi.fn().mockRejectedValue(new Error("Wrapped for Android")),
}));
vi.mock("firebase/firestore", () => ({
  collection: vi.fn(), query: vi.fn(), where: vi.fn(),
  doc: vi.fn(), getDoc: async () => mocks.directory(),
  getDocs: async () => ({ docs: mocks.rows() }),
}));
vi.mock("firebase/functions", () => ({
  httpsCallable: (_functions: unknown, name: string) => (data: unknown) => mocks.callable(name, data),
}));

afterEach(() => vi.unstubAllGlobals());

it("opens an Android upload on a fresh browser using the signed-in account's recovery key", async () => {
  const raw = crypto.getRandomValues(new Uint8Array(32));
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
    account_recovery_ready: true,
    encrypted_metadata: toBase64(await encrypted(JSON.stringify({ name: "phone.txt", mimeType: "text/plain" }))),
    ciphertext_size: ciphertext.length,
    ciphertext_sha256: [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join(""),
    wrapped_keys: { "user-a": "android-only-envelope" },
  };
  mocks.rows.mockReturnValue([
    { id: "phone-upload", data: () => row },
    { id: "older-upload", data: () => ({ ...row, account_recovery_ready: false }) },
  ]);
  mocks.callable.mockImplementation(async (name) => ({
    data: name === "mediaAccountAccessKey"
      ? { accountAccessKey: toBase64(raw) }
      : { url: "https://test.r2.cloudflarestorage.com/download" },
  }));

  const [document, older] = await listBrowserDocuments({ uid: "user-a", username: "tester", role: "USER" });
  expect(document).toMatchObject({ approved: true, name: "phone.txt", mimeType: "text/plain" });
  expect(older.approved).toBe(false);
  expect(mocks.callable).toHaveBeenCalledExactlyOnceWith("mediaAccountAccessKey", { mediaId: "phone-upload" });

  const fetchBlob = globalThis.fetch;
  vi.stubGlobal("fetch", (url: string) =>
    url.startsWith("blob:") ? fetchBlob(url) : Promise.resolve(new Response(ciphertext)));
  const url = await openBrowserDocument(document);
  try {
    expect(await (await fetch(url)).text()).toBe("Test document");
  } finally {
    URL.revokeObjectURL(url);
  }
});

const owner = { uid: "user-a", username: "tester", role: "USER" } as const;

async function configureUpload(mode: "SINGLE" | "MULTIPART") {
  mocks.callable.mockClear();
  const keys = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 2048,
    publicExponent: Uint8Array.of(1, 0, 1), hash: "SHA-1" }, true, ["encrypt", "decrypt"]);
  const publicKey = toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", keys.publicKey)));
  mocks.directory.mockReturnValue({ data: () => ({ publicKey }), get: (field: string) =>
    field === "uid" ? owner.uid : publicKey });
  mocks.rows.mockReturnValue([{ data: () => ({ uid: "unrelated-account", publicKey, deviceId: "unrelated-device" }) }]);
  mocks.callable.mockImplementation(async (name, data) => {
    if (name === "mediaCreateUpload") return { data: { mode, mediaId: "fixture-media", partSize: 24 } };
    if (name === "mediaUploadPartTicket") return { data: { url: `https://storage.example.test/${data.partNumber}`, headers: { "Content-Type": "application/octet-stream" } } };
    return { data: {} };
  });
  return keys.privateKey;
}

it("uploads only ciphertext, verifies multipart receipts and wraps the same key for the owner", async () => {
  const privateKey = await configureUpload("MULTIPART");
  const parts: Uint8Array[] = [];
  const send = vi.fn(async (_url: string, request: { body: Uint8Array }) => {
    parts.push(request.body.slice());
    return new Response(null, { headers: { ETag: `receipt-${parts.length}` } });
  });
  vi.stubGlobal("fetch", send);
  const report = vi.fn();
  expect(await uploadBrowserDocument(new File(["private synthetic text"], "fixture.txt", { type: "text/plain" }), owner, report)).toBe("fixture-media");
  const creation = mocks.callable.mock.calls.find(([name]) => name === "mediaCreateUpload")![1];
  expect(creation).toMatchObject({ allowedUsers: [owner.uid], deviceWrappedKeys: {}, encryptionVersion: 2 });
  expect(parts.length).toBeGreaterThan(1);
  expect(parts.slice(0, -1).every(part => part.length === 24)).toBe(true);
  const ciphertext = Uint8Array.from(parts.flatMap(part => [...part]));
  expect(new TextDecoder().decode(ciphertext.slice(0, 4))).toBe("OLVM");
  expect(new TextDecoder().decode(ciphertext)).not.toContain("private synthetic text");
  const wrappedKey = Uint8Array.from(atob(creation.wrappedKeys[owner.uid]), c => c.charCodeAt(0));
  const rawKey = new Uint8Array(await crypto.subtle.decrypt("RSA-OAEP", privateKey, wrappedKey));
  expect(toBase64(rawKey)).toBe(creation.recoveryKeyToSeal);
  const digest = Buffer.from(await crypto.subtle.digest("SHA-256", ciphertext)).toString("hex");
  expect(creation.ciphertextSha256).toBe(digest);
  expect(creation.ciphertextSize).toBe(ciphertext.byteLength);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: ciphertext.slice(6, 18) }, await importKey(rawKey), ciphertext.slice(18));
  expect(new TextDecoder().decode(plain)).toBe("private synthetic text");
  expect(mocks.callable).toHaveBeenCalledWith("mediaCompleteUpload", {
    jobId: creation.jobId, parts: parts.map((_, index) => ({ partNumber: index + 1, etag: `receipt-${index + 1}` })),
  });
  expect(report).toHaveBeenLastCalledWith(1);
});

it("aborts a multipart upload with no receipt and allows a single PUT without an ETag", async () => {
  const file = new File(["fixture"], "fixture.txt");
  await configureUpload("MULTIPART");
  const send = vi.fn(async () => new Response(null));
  vi.stubGlobal("fetch", send);
  await expect(uploadBrowserDocument(file, owner)).rejects.toThrow("upload part receipt");
  expect(mocks.callable.mock.calls.map(([name]) => name)).toEqual(["mediaCreateUpload", "mediaUploadPartTicket", "mediaAbortUpload"]);
  await configureUpload("SINGLE");
  send.mockClear();
  expect(await uploadBrowserDocument(file, owner)).toBe("fixture-media");
  expect(send).toHaveBeenCalledTimes(1);
  expect(mocks.callable).toHaveBeenLastCalledWith("mediaCompleteUpload", { jobId: expect.any(String), parts: [] });
});

it("rejects tampered ciphertext before decryption and blocks a non-HTTPS ticket", async () => {
  const ciphertext = Uint8Array.of(1, 2, 3, 4);
  const document: BrowserDocument = { id: "fixture-media", name: "fixture.txt", mimeType: "text/plain",
    ownerUid: owner.uid, createdAt: 0, approved: true, approvalMessage: "", evidence: false,
    ciphertextSha256: "0".repeat(64), ciphertextSize: ciphertext.length,
    key: await importKey(crypto.getRandomValues(new Uint8Array(32))) };
  mocks.callable.mockResolvedValue({ data: { url: "https://storage.example.test/fixture" } });
  const send = vi.fn(async () => new Response(ciphertext));
  vi.stubGlobal("fetch", send);
  await expect(openBrowserDocument(document)).rejects.toThrow("integrity check");
  await expect(openBrowserDocument({ ...document, ciphertextSize: 100 })).rejects.toThrow("size does not match");
  mocks.callable.mockResolvedValueOnce({ data: { url: "http://storage.example.test/fixture" } });
  await expect(openBrowserDocument(document)).rejects.toThrow("invalid");
  expect(send).toHaveBeenCalledTimes(2);
});
