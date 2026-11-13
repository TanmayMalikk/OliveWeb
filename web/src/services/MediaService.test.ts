import { afterEach, expect, it, vi } from "vitest";
import { importKey, toBase64 } from "./EncryptionService";
import { listBrowserDocuments, openBrowserDocument } from "./MediaService";

const mocks = vi.hoisted(() => ({
  rows: vi.fn(),
  callable: vi.fn(),
}));

vi.mock("./AccountService", () => ({ firebase: async () => ({ db: {}, functions: {} }) }));
vi.mock("./MessagingService", () => ({
  messageDevice: async () => ({ deviceId: "new-browser" }),
  register: vi.fn(),
  unwrapRoomKeyBytes: vi.fn().mockRejectedValue(new Error("Wrapped for Android")),
}));
vi.mock("firebase/firestore", () => ({
  collection: vi.fn(), query: vi.fn(), where: vi.fn(),
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
