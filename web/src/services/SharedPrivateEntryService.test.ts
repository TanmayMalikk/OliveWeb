import { afterEach, expect, it, vi } from "vitest";

vi.mock("./AccountService", () => ({
  firebase: async () => ({ db: {} }),
  accountKeysetSession: { current: () => null },
}));
vi.mock("./MessagingService", () => ({
  messageDevice: async () => ({ deviceId: "browser-dev" }),
  unwrapRoomKeyBytes: async () => new Uint8Array(),
}));

import { clearDocumentPreviewCache, getCachedDocumentPreview, loadDocumentPreview } from "./DocumentPreviewCache";
import { importKey, toBase64 } from "./EncryptionService";
import { decryptSharedEntryPayload } from "./SharedPrivateEntryService";

afterEach(() => {
  clearDocumentPreviewCache();
  vi.restoreAllMocks();
});

it("decrypts shared Android private entries with incident details and attachmentIds", async () => {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const key = await importKey(raw);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = {
    id: "entry-42",
    timestamp: 1700000000000,
    updatedAt: 1700000500000,
    kind: "Incident",
    title: "Front door incident",
    body: "Documented what happened safely.",
    incidentDate: "2026-11-24",
    incidentTime: "18:30",
    incidentLocation: "Main St",
    attachmentIds: ["doc-1", "doc-2", "doc-1"],
  };
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, tagLength: 128 },
    key,
    new TextEncoder().encode(JSON.stringify(payload)),
  );
  const combined = new Uint8Array([...iv, ...new Uint8Array(encrypted)]);

  const decoded = await decryptSharedEntryPayload("entry-42", toBase64(combined), raw);
  expect(decoded.id).toBe("entry-42");
  expect(decoded.kind).toBe("Incident");
  expect(decoded.title).toBe("Front door incident");
  expect(decoded.incidentDate).toBe("2026-11-24");
  expect(decoded.incidentLocation).toBe("Main St");
  expect(decoded.attachmentIds).toEqual(["doc-1", "doc-2"]);
});

it("clears in-memory preview cache cleanly", async () => {
  const identity = { uid: "u-1", username: "alice", role: "USER" as const };
  const result = await loadDocumentPreview(identity, {
    id: "doc-unapproved",
    name: "photo.jpg",
    mimeType: "image/jpeg",
    ownerUid: "u-1",
    createdAt: 1,
    approved: false,
    approvalMessage: "Locked",
    ciphertextSize: 100,
    ciphertextSha256: "abc",
    evidence: false,
  });
  expect(result?.kind).toBe("image");
  expect(getCachedDocumentPreview("u-1", "doc-unapproved")).toBeDefined();
  clearDocumentPreviewCache();
  expect(getCachedDocumentPreview("u-1", "doc-unapproved")).toBeUndefined();
});
