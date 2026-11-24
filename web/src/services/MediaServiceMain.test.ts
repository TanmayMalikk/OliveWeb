import { afterEach, expect, it, vi } from "vitest";
import { importKey, toBase64 } from "./EncryptionService";
import { openBrowserDocument, uploadBrowserDocument, type BrowserDocument } from "./MediaService";

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

const owner = { uid: "user-a", username: "tester", role: "USER" } as const;

async function configureUpload(mode: "SINGLE" | "MULTIPART") {
  mocks.callable.mockClear();
  const keys = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 3072,
    publicExponent: Uint8Array.of(1, 0, 1), hash: "SHA-256" }, true, ["encrypt", "decrypt"]);
  const publicKey = toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", keys.publicKey)));
  const fields: Record<string, unknown> = { uid: owner.uid, ownerUid: owner.uid, publicKey,
    keyId: "123e4567-e89b-42d3-a456-426614174000", epoch: 1, algorithm: "RSA-OAEP-256" };
  mocks.directory.mockReturnValue({ data: () => fields, get: (field: string) => fields[field] });
  mocks.rows.mockReturnValue([{ data: () => ({
    uid: owner.uid, publicKey, deviceId: "approved-browser", mgf1Digest: "SHA-256",
  }) }]);
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
  expect(creation).not.toHaveProperty("recoveryKeyToSeal");
  expect(creation.accountWrappedKeys[owner.uid]).toMatchObject({
    keyId: "123e4567-e89b-42d3-a456-426614174000", epoch: 1, algorithm: "RSA-OAEP-256",
  });
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
  expect(mocks.callable.mock.calls.map(([name]) => name)).toEqual(["messagingAuthorizePeer", "mediaCreateUpload", "mediaUploadPartTicket", "mediaAbortUpload"]);
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
