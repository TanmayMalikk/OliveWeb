import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { importKey, toBase64 } from "./EncryptionService";
import { listBrowserDocuments, observeBrowserDocuments, observeDocumentRecipientDevices, openBrowserDocument, openVoiceMessage, prepareVoiceMessage, publishVoiceMessage, setBrowserDocumentEvidence, shareBrowserPlanDocument, uploadBrowserDocument, type BrowserDocument } from "./MediaService";

const mocks = vi.hoisted(() => ({
  rows: vi.fn(),
  callable: vi.fn(),
  directory: vi.fn(),
  serverRow: vi.fn(),
  snapshot: vi.fn(),
  unwrap: vi.fn(),
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
  register: async () => undefined,
  unwrapRoomKeyBytes: (...args: unknown[]) => mocks.unwrap(...args),
  encryptChatMessage: async (text: string) => `encrypted:${text}`,
}));
vi.mock("firebase/firestore", () => ({
  collection: (...parts: unknown[]) => parts.join("/"),
  doc: (...parts: unknown[]) => parts.join("/"),
  query: vi.fn(), where: vi.fn(),
  getDoc: (reference: unknown) => mocks.getDoc(reference),
  getDocFromServer: (reference: unknown) => mocks.serverRow(reference),
  onSnapshot: (...args: unknown[]) => mocks.snapshot(...args),
  getDocs: (reference: unknown) => mocks.getDocs(reference),
}));
vi.mock("firebase/functions", () => ({
  httpsCallable: (_functions: unknown, name: string) => (data: unknown) => mocks.callable(name, data),
}));

beforeEach(() => {
  mocks.unwrap.mockRejectedValue(new Error("Wrapped for Android"));
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

const owner = { uid: "user-a", username: "tester", role: "USER" } as const;
const ACCOUNT_KEY_ID = "123e4567-e89b-42d3-a456-426614174000";

async function configureUpload(mode: "SINGLE" | "MULTIPART") {
  mocks.callable.mockClear();
  const keys = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 3072,
    publicExponent: Uint8Array.of(1, 0, 1), hash: "SHA-256" }, true, ["encrypt", "decrypt"]);
  const publicKey = toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", keys.publicKey)));
  const fields: Record<string, unknown> = {
    uid: owner.uid, ownerUid: owner.uid, publicKey,
    keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256",
  };
  mocks.directory.mockReturnValue({ data: () => fields, get: (field: string) => fields[field] });
  mocks.rows.mockReturnValue([{ data: () => ({ uid: "unrelated-account", publicKey, deviceId: "unrelated-device", mgf1Digest: "SHA-256" }) }]);
  mocks.callable.mockImplementation(async (name, data) => {
    if (name === "mediaCreateUpload") return { data: { mode, mediaId: "fixture-media", partSize: 24 } };
    if (name === "mediaUploadPartTicket") return { data: { url: `https://storage.example.test/${data.partNumber}`, headers: { "Content-Type": "application/octet-stream" } } };
    return { data: {} };
  });
  return { publicKey };
}

it("publishes an owner recording with keys for the current participants and rejects stale membership", async () => {
  const { publicKey } = await configureUpload("SINGLE");
  mocks.getDoc.mockImplementation(async (reference: string) => {
    const id = reference.split("/").at(-1) ?? "";
    const uid = id === "tester" ? owner.uid : id;
    return {
      get: (field: string) => ({
        uid, ownerUid: uid, keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256", publicKey,
      })[field],
    };
  });
  mocks.rows.mockReturnValue([{ data: () => ({ uid: "friend", publicKey, deviceId: "friend-phone", mgf1Digest: "SHA-256" }) }]);
  mocks.unwrap.mockResolvedValue(crypto.getRandomValues(new Uint8Array(32)));
  const chat: Parameters<typeof prepareVoiceMessage>[1] = {
    id: "room",
    friendshipId: "friendship",
    participantIds: [owner.uid, "friend"],
    participantUsernames: { friend: "friend" },
    lastMessage: "",
    updatedAt: 0,
    type: "DIRECT",
    ownerUid: owner.uid,
    staffUid: "",
    adminUid: "",
    supportOrigin: "",
    supportTag: "",
    closedAt: 0,
  };
  const file = { owner_id: owner.uid, wrapped_keys: { [owner.uid]: "owner-envelope" } };
  mocks.serverRow.mockImplementation((id: string) => ({ data: () => id.endsWith("/room") ? chat : file }));
  const pending = await prepareVoiceMessage("recording", chat, await importKey(crypto.getRandomValues(new Uint8Array(32))));
  expect(pending).toMatchObject({ mediaId: "recording", conversationId: "room", ciphertext: "encrypted:SHARED_VOICE:recording" });
  await publishVoiceMessage(pending, owner);
  expect(mocks.callable).toHaveBeenLastCalledWith("mediaShareInChat", {
    ...pending, recipientUids: ["friend"], wrappedKeys: { [owner.uid]: "owner-envelope", friend: expect.any(String) },
    deviceWrappedKeys: { "friend--friend-phone": expect.any(String) },
    accountWrappedKeys: {
      [owner.uid]: expect.objectContaining({ keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256" }),
      friend: expect.objectContaining({ keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256" }),
    },
  });
  chat.participantIds = ["friend"];
  await expect(publishVoiceMessage(pending, owner)).rejects.toThrow("no longer available");
  expect(mocks.callable.mock.calls.filter(([name]) => name === "mediaShareInChat")).toHaveLength(1);
});

it("adds plan access for available recipients, keeps existing device access and reports missing recipients", async () => {
  const { publicKey } = await configureUpload("SINGLE");
  const file = { owner_id: owner.uid, wrapped_keys: { [owner.uid]: "owner-envelope" },
    allowed_users: [owner.uid, "existing"], device_wrapped_keys: { "existing--phone": "existing-envelope" } };
  mocks.getDoc.mockImplementation(async (reference: string) => {
    if (reference.endsWith("/files/document") || reference === "files/document") return { data: () => file };
    const id = reference.split("/").at(-1) ?? "";
    if (id === "missing" || id === "unregistered") return { get: () => undefined };
    const uid = id === "tester" ? owner.uid : id;
    return {
      get: (field: string) => ({
        uid, ownerUid: uid, keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256", publicKey,
      })[field],
    };
  });
  mocks.rows.mockReturnValue(["existing", "new-friend"].map((uid) => ({ data: () => ({ uid, publicKey, deviceId: "phone", mgf1Digest: "SHA-256" }) })));
  mocks.unwrap.mockImplementation(async () => crypto.getRandomValues(new Uint8Array(32)));
  await shareBrowserPlanDocument("document", owner, []);
  await expect(shareBrowserPlanDocument("document", owner, [
    { uid: "existing", username: "existing" }, { uid: "new-friend", username: "new-friend" }, { uid: "missing", username: "unregistered" },
  ])).rejects.toThrow("sign in on their device");
  expect(mocks.callable).toHaveBeenLastCalledWith("mediaUpdateAccess", {
    mediaId: "document", forPlan: true,
    wrappedKeys: { [owner.uid]: "owner-envelope", "new-friend": expect.any(String) },
    deviceWrappedKeys: { "new-friend--phone": expect.any(String) },
    accountWrappedKeys: {
      [owner.uid]: expect.objectContaining({ keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256" }),
      "new-friend": expect.objectContaining({ keyId: ACCOUNT_KEY_ID, epoch: 1, algorithm: "RSA-OAEP-256" }),
    },
  });
  await expect(shareBrowserPlanDocument("document", { ...owner, uid: "other" }, [{ uid: "existing", username: "existing" }])).rejects.toThrow("document owner");
  file.wrapped_keys[owner.uid] = "";
  await expect(shareBrowserPlanDocument("document", owner, [{ uid: "existing", username: "existing" }])).rejects.toThrow("encrypted copy");
});

it("discards superseded document snapshots, cleans up listeners and saves evidence separately", async () => {
  const unsubscribe = vi.fn();
  mocks.snapshot.mockReturnValue(unsubscribe);
  const update = vi.fn();
  const error = vi.fn();
  const stop = await observeBrowserDocuments(owner, update, error);
  const [, emit, fail] = mocks.snapshot.mock.calls[0];
  const pendingError = new Error("Disconnected");
  emit({ docs: [{ id: "stale", data: () => ({}) }] });
  emit({ docs: [{ id: "current", data: () => ({ owner_id: owner.uid, evidence: true }) }] });
  await vi.waitFor(() => expect(update).toHaveBeenCalledExactlyOnceWith([expect.objectContaining({ id: "current", evidence: true })]));
  fail(pendingError);
  expect(error).toHaveBeenCalledWith(pendingError);
  stop();
  expect(unsubscribe).toHaveBeenCalledOnce();
  const changed = vi.fn();
  const stopDevices = await observeDocumentRecipientDevices([{ username: "friend" }, { username: "neighbor" }], changed);
  const [, emitDevice, ignoreDeviceError] = mocks.snapshot.mock.calls[1];
  emitDevice();
  ignoreDeviceError(pendingError);
  expect(changed).toHaveBeenCalledOnce();
  stopDevices();
  expect(unsubscribe).toHaveBeenCalledTimes(3);
  await setBrowserDocumentEvidence("current", true);
  expect(mocks.callable).toHaveBeenLastCalledWith("mediaUpdateEvidence", { mediaId: "current", evidence: true });
});

it("reports missing recordings and storage errors without producing a preview", async () => {
  mocks.serverRow.mockReturnValue({ exists: () => false });
  await expect(openVoiceMessage("missing", owner)).rejects.toThrow("unavailable");
  mocks.serverRow.mockReturnValue({ id: "document", exists: () => true, data: () => ({}) });
  await expect(openVoiceMessage("document", owner)).rejects.toThrow("could not be opened");
  const document: BrowserDocument = { id: "document", name: "fixture.pdf", mimeType: "application/pdf", ownerUid: owner.uid,
    createdAt: 0, approved: false, approvalMessage: "Access unavailable", evidence: false, ciphertextSha256: "", ciphertextSize: 0 };
  await expect(openBrowserDocument(document)).rejects.toThrow("Access unavailable");
  const unlocked = { ...document, key: await importKey(crypto.getRandomValues(new Uint8Array(32))) };
  mocks.callable.mockResolvedValue({ data: { url: "https://storage.example.test/fixture" } });
  const send = vi.fn().mockRejectedValueOnce(new Error("Offline")).mockResolvedValueOnce(new Response(null, { status: 503 }));
  vi.stubGlobal("fetch", send);
  await expect(openBrowserDocument(unlocked)).rejects.toThrow("could not reach encrypted storage");
  await expect(openBrowserDocument(unlocked)).rejects.toThrow("Cloud download failed (503)");
});

it("tags voice uploads as chat-only in encrypted metadata without exposing metadata in plaintext", async () => {
  const keyId = "123e4567-e89b-42d3-a456-426614174000";
  const pair = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 3072, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const publicKey = toBase64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
  mocks.getDoc.mockImplementation(async (reference: string) => reference.includes("accountKeyDirectories")
    ? { get: (field: string) => ({ ownerUid: owner.uid, keyId, epoch: 1, algorithm: "RSA-OAEP-256", publicKey })[field] }
    : { get: (field: string) => ({ uid: owner.uid, publicKey })[field] });
  mocks.getDocs.mockResolvedValue({ docs: [{ data: () => ({
    uid: owner.uid, deviceId: "new-browser", publicKey, mgf1Digest: "SHA-256",
  }) }] });
  mocks.callable.mockImplementation(async (name: string) => {
    if (name === "mediaCreateUpload") return { data: { mediaId: "voice-upload", mode: "SINGLE", partSize: 0 } };
    if (name === "mediaUploadPartTicket") return { data: { url: "https://storage.example.test/upload" } };
    return { data: {} };
  });
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));

  await expect(uploadBrowserDocument(
    new File([new Uint8Array([9, 8, 7])], "Voice message.webm", { type: "audio/webm;codecs=opus" }),
    owner,
    undefined,
    { chatOnly: true },
  )).resolves.toBe("voice-upload");

  const create = mocks.callable.mock.calls.find(([name]) => name === "mediaCreateUpload")?.[1] as {
    encryptedMetadata: string;
    deviceWrappedKeys: Record<string, string>;
  };
  const wrappedBytes = Uint8Array.from(atob(create.deviceWrappedKeys[`${owner.uid}--new-browser`]), (c) => c.charCodeAt(0));
  const rawAes = new Uint8Array(await crypto.subtle.decrypt({ name: "RSA-OAEP" }, pair.privateKey, wrappedBytes));
  const aesKey = await importKey(rawAes);
  const metaBytes = Uint8Array.from(atob(create.encryptedMetadata), (c) => c.charCodeAt(0));
  const decodedMeta = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: metaBytes.slice(0, 12), tagLength: 128 },
    aesKey,
    metaBytes.slice(12),
  )));
  expect(decodedMeta).toMatchObject({
    name: "Voice message.webm",
    mimeType: "audio/webm;codecs=opus",
    kind: "AUDIO",
    ocrReview: "NOT_APPLICABLE",
    chatOnly: true,
  });
});

it("excludes chat-only and legacy voice messages from document lists while still opening voice messages in chat", async () => {
  const keyId = "123e4567-e89b-42d3-a456-426614174000";
  const pair = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 3072, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const key = await importKey(raw);
  const encoder = new TextEncoder();
  const accountWrapper = toBase64(new Uint8Array(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, pair.publicKey, raw)));
  mocks.keyset.mockReturnValue({
    accountId: owner.uid, keyId, epoch: 1, publicKey: pair.publicKey, privateKey: pair.privateKey,
  });
  async function encrypted(value: string, header: number[] = []) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const body = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoder.encode(value));
    return Uint8Array.from([...header, ...iv, ...new Uint8Array(body)]);
  }
  const voiceCipher = await encrypted("voice-audio", [0x4f, 0x4c, 0x56, 0x4d, 1, 12]);
  const voiceDigest = new Uint8Array(await crypto.subtle.digest("SHA-256", voiceCipher));
  const makeRow = async (id: string, meta: Record<string, unknown>) => {
    const encryptedMetadata = toBase64(await encrypted(JSON.stringify(meta)));
    return {
      id,
      data: () => ({
        owner_id: owner.uid,
        object_key: `users/${owner.uid}/media/${id}`,
        encrypted_metadata: encryptedMetadata,
        ciphertext_size: voiceCipher.length,
        ciphertext_sha256: [...voiceDigest].map((byte) => byte.toString(16).padStart(2, "0")).join(""),
        wrapped_keys: { [owner.uid]: "android-only" },
        account_wrapped_keys: { [owner.uid]: { keyId, epoch: 1, algorithm: "RSA-OAEP-256", wrappedKey: accountWrapper } },
      }),
    };
  };
  const docRow = await makeRow("doc-1", { name: "plan.pdf", mimeType: "application/pdf" });
  const taggedVoiceRow = await makeRow("voice-tagged", { name: "Audio.m4a", mimeType: "audio/mp4", chatOnly: true });
  const legacyVoiceRow = await makeRow("voice-legacy", { name: "Voice message.m4a", mimeType: "audio/mp4" });
  const manualAudioRow = await makeRow("manual-audio", { name: "Recording.m4a", mimeType: "audio/mp4" });

  mocks.getDocs.mockResolvedValue({ docs: [docRow, taggedVoiceRow, legacyVoiceRow, manualAudioRow] });
  const listed = await listBrowserDocuments(owner);
  expect(listed.map((d) => d.id)).toEqual(["doc-1", "manual-audio"]);

  const unsubscribe = vi.fn();
  mocks.snapshot.mockReturnValue(unsubscribe);
  const update = vi.fn();
  const stop = await observeBrowserDocuments(owner, update, vi.fn());
  const [, emit] = mocks.snapshot.mock.calls[0];
  emit({ docs: [docRow, taggedVoiceRow, legacyVoiceRow, manualAudioRow] });
  await vi.waitFor(() => expect(update).toHaveBeenCalledOnce());
  expect(update.mock.calls[0][0].map((d: BrowserDocument) => d.id)).toEqual(["doc-1", "manual-audio"]);
  stop();

  mocks.serverRow.mockResolvedValue({ id: taggedVoiceRow.id, exists: () => true, data: taggedVoiceRow.data });
  mocks.callable.mockResolvedValue({ data: { url: "https://storage.example.test/voice" } });
  const fetchBlob = globalThis.fetch;
  vi.stubGlobal("fetch", (url: string) =>
    url.startsWith("blob:") ? fetchBlob(url) : Promise.resolve(new Response(voiceCipher)));
  const url = await openVoiceMessage("voice-tagged", owner);
  try {
    expect(await (await fetch(url)).text()).toBe("voice-audio");
  } finally {
    URL.revokeObjectURL(url);
  }
});
