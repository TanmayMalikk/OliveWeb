// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installDomMocks } from "../test-utils/domTestHarness";
import type { BrowserDocument } from "./MediaService";

const mockLoadById = vi.fn();
const mockOpenDoc = vi.fn();
const mockHttpsCallable = vi.fn();
const mockOnSnapshot = vi.fn();
const mockCollection = vi.fn();
const mockOnValue = vi.fn();
const mockRef = vi.fn();
const mockDbMap = new Map<string, unknown>();

let mockCloudMode = true;
const mockFirebase = {
  db: { type: "firestore" },
  rtdb: { type: "rtdb" },
  functions: { type: "functions" },
};

vi.mock("./LocalDataStore", () => ({
  database: Promise.resolve({
    get: vi.fn(async (_store: string, key: string) => mockDbMap.get(key)),
    put: vi.fn(async (_store: string, val: unknown, key: string) => {
      mockDbMap.set(key, val);
    }),
    delete: vi.fn(async (_store: string, key: string) => {
      mockDbMap.delete(key);
    }),
  }),
}));

vi.mock("./MediaService", () => ({
  loadBrowserDocumentById: (...args: unknown[]) => mockLoadById(...args),
  openBrowserDocument: (...args: unknown[]) => mockOpenDoc(...args),
}));

vi.mock("./AccountService", () => ({
  get cloudMode() {
    return mockCloudMode;
  },
  firebase: vi.fn(async () => mockFirebase),
}));

vi.mock("firebase/functions", () => ({
  httpsCallable: (...args: unknown[]) => mockHttpsCallable(...args),
}));

vi.mock("firebase/firestore", () => ({
  collection: (...args: unknown[]) => mockCollection(...args),
  onSnapshot: (...args: unknown[]) => mockOnSnapshot(...args),
}));

vi.mock("firebase/database", () => ({
  ref: (...args: unknown[]) => mockRef(...args),
  onValue: (...args: unknown[]) => mockOnValue(...args),
}));

import {
  clearDocumentPreviewCache,
  getCachedDocumentPreview,
  loadDocumentPreview,
  loadDocumentPreviewById,
} from "./DocumentPreviewCache";
import {
  decryptOpaqueRecoveryEnvelope,
  encryptOpaqueRecoveryEnvelope,
  createOpaqueRecoveryFlow,
} from "./OpaqueRecoveryFlow";
import {
  observeAdminLiveLocations,
  observeEmergencyReports,
  openEmergencyReportPdf,
  parseAdminLiveLocation,
  parseEmergencyReport,
  setEmergencyResolved,
} from "./EmergencyResponseService";
import { readCover, writeCover } from "./CoverStateStore";
import { saveOrganizationProfile } from "./OrganizationProfileService";
import {
  createAccount,
  deleteAccount,
  listAccounts,
  listStaffWorkloads,
  loadAdminSummary,
} from "../users/admin/AdminAccountApi";

const identity = { uid: "uid-1", username: "user_one", role: "USER" as const };
const dummyKey = {} as CryptoKey;

function docFixture(id: string, mimeType: string, name: string, approved = true): BrowserDocument {
  return {
    id,
    ownerUid: "uid-1",
    name,
    mimeType,
    ciphertextSize: 120,
    ciphertextSha256: "a".repeat(64),
    createdAt: 1000,
    evidence: false,
    approved,
    approvalMessage: approved ? "" : "Needs approval",
    key: approved ? dummyKey : undefined,
  };
}

describe("Web services coverage", () => {
  beforeEach(() => {
    installDomMocks();
    clearDocumentPreviewCache();
    mockDbMap.clear();
    mockCloudMode = true;
    vi.clearAllMocks();
  });

  it("loads, caches, downscales, and clears blurred previews for images, videos, PDFs, text, and errors", async () => {
    const origImage = globalThis.Image;
    const origFetch = globalThis.fetch;
    class FakeImage {
      naturalWidth = 800;
      naturalHeight = 600;
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      set src(_v: string) {
        queueMicrotask(() => this.onload?.());
      }
    }
    globalThis.Image = FakeImage as unknown as typeof Image;
    globalThis.fetch = vi.fn(async () => ({ text: async () => "Hello document text preview" })) as unknown as typeof fetch;

    const origToBlob = HTMLCanvasElement.prototype.toBlob;
    HTMLCanvasElement.prototype.toBlob = function (cb) {
      cb?.(new Blob(["jpeg"], { type: "image/jpeg" }));
    };

    try {
      mockOpenDoc.mockResolvedValue("blob:img-1");
      const imgDoc = docFixture("img-1", "image/jpeg", "photo.jpg");
      const first = await loadDocumentPreview(identity, imgDoc);
      expect(first?.kind).toBe("image");
      expect(getCachedDocumentPreview("uid-1", "img-1")).toEqual(first);
      // Cache hit returns identical entry.
      expect(await loadDocumentPreview(identity, imgDoc)).toBe(first);

      // PDF & text & unapproved/other documents.
      mockOpenDoc.mockResolvedValueOnce("blob:pdf-1");
      expect((await loadDocumentPreview(identity, docFixture("pdf-1", "application/pdf", "r.pdf")))?.kind).toBe("pdf");

      mockOpenDoc.mockResolvedValueOnce("blob:txt-1");
      const txtRes = await loadDocumentPreview(identity, docFixture("txt-1", "text/plain", "note.txt"));
      expect(txtRes?.text).toContain("Hello document text");

      const unapproved = await loadDocumentPreview(identity, docFixture("un-1", "image/png", "x.png", false));
      expect(unapproved?.kind).toBe("image");
      expect(unapproved?.url).toBeUndefined();

      // By ID path + failure path.
      mockLoadById.mockResolvedValueOnce(docFixture("byid-1", "application/octet-stream", "bin.dat"));
      expect((await loadDocumentPreviewById(identity, "byid-1"))?.kind).toBe("other");

      mockLoadById.mockRejectedValueOnce(new Error("Missing"));
      expect(await loadDocumentPreviewById(identity, "missing-1")).toBeNull();

      // Video capture path.
      const origCreate = document.createElement.bind(document);
      vi.spyOn(document, "createElement").mockImplementation(((tag: string) => {
        const el = origCreate(tag);
        if (tag === "video") {
          Object.defineProperty(el, "videoWidth", { value: 640 });
          Object.defineProperty(el, "videoHeight", { value: 360 });
          Object.defineProperty(el, "src", {
            set() {
              queueMicrotask(() => (el as HTMLVideoElement).onloadeddata?.(new Event("loadeddata")));
            },
          });
        }
        return el;
      }) as typeof document.createElement);
      mockOpenDoc.mockResolvedValueOnce("blob:vid-1");
      const vidRes = await loadDocumentPreview(identity, docFixture("vid-1", "video/mp4", "clip.mp4"));
      expect(vidRes?.kind).toBe("video");
      expect(vidRes?.url).toMatch(/^blob:/);
    } finally {
      globalThis.Image = origImage;
      globalThis.fetch = origFetch;
      HTMLCanvasElement.prototype.toBlob = origToBlob;
      vi.restoreAllMocks();
    }
  });

  it("handles CoverStateStore, OrganizationProfileService, AdminAccountApi, and EmergencyResponseService", async () => {
    expect(await readCover("theme", "fallback")).toBe("fallback");
    await writeCover("theme", { active: true });
    expect(await readCover("theme", { active: false })).toEqual({ active: true });

    const fnStub = vi.fn(async (input?: unknown) => ({
      data: input && (input as { view?: string }).view === "SUMMARY"
        ? { counts: { USER: 1, SAFE_CONTACT: 0, STAFF: 1, ADMIN: 1 }, tickets: { open: 2, closed: 1 } }
        : input && (input as { view?: string }).view
          ? { accounts: [{ uid: "u1", username: "user_one", role: "USER", fullName: "U", email: "", phone: "", profileComplete: true, protected: false }] }
          : { profile: { username: "staff", fullName: "Staff", role: "STAFF" } },
    }));
    mockHttpsCallable.mockImplementation(() => fnStub);

    expect((await saveOrganizationProfile({ fullName: "Staff", dob: "1990-01-01", email: "s@e.ca", phone: "555" })).username).toBe("staff");
    expect((await listAccounts()).length).toBe(1);
    expect((await loadAdminSummary()).tickets.open).toBe(2);
    expect((await listStaffWorkloads()).length).toBe(1);
    await createAccount({ username: "new_staff" });
    await deleteAccount("u1");

    mockCloudMode = false;
    await expect(saveOrganizationProfile({ fullName: "S", dob: "1990-01-01", email: "a@b.ca", phone: "1" })).rejects.toThrow();
    await expect(listAccounts()).rejects.toThrow();
    mockCloudMode = true;

    // EmergencyResponseService parsing + observers + actions.
    expect(parseEmergencyReport("r1", null)).toBeNull();
    expect(parseEmergencyReport("r1", { ownerUid: "u1", createdAt: { toMillis: () => 500 }, status: "ACTIVE" })?.ownerName).toBe("u1");
    expect(parseAdminLiveLocation("u1", { latitude: 200, longitude: 0, updatedAt: 1 })).toBeNull();
    expect(parseAdminLiveLocation("u1", { latitude: 53.5, longitude: -113.5, updatedAt: 10, active: true, accuracy: 8 })?.name).toBe("u1");

    const updates: unknown[] = [];
    const errors: string[] = [];
    mockOnSnapshot.mockImplementation((_col, onNext, onErr) => {
      onNext({ docs: [{ id: "r1", data: () => ({ ownerUid: "u1", ownerName: "User", createdAt: 20, status: "OPEN" }) }] });
      onErr(new Error("snapshot err"));
      return () => {};
    });
    await observeEmergencyReports((r) => updates.push(r), (e) => errors.push(e));
    expect(updates.length).toBe(1);
    expect(errors).toContain("snapshot err");

    mockOnValue.mockImplementation((_ref, onNext, onErr) => {
      onNext({ val: () => ({ u1: { latitude: 53.5, longitude: -113.5, updatedAt: 100, active: true } }) });
      onErr(new Error("rtdb err"));
      return () => {};
    });
    await observeAdminLiveLocations((l) => updates.push(l), (e) => errors.push(e));
    expect(errors).toContain("rtdb err");

    await setEmergencyResolved("r1", true);
    await setEmergencyResolved("r1", false);
    mockLoadById.mockResolvedValueOnce(docFixture("er-1", "application/pdf", "er.pdf"));
    mockOpenDoc.mockResolvedValueOnce("blob:er");
    expect((await openEmergencyReportPdf(identity, "er-1")).url).toBe("blob:er");
  });

  it("handles OpaqueRecoveryFlow error mapping and envelope validation", async () => {
    const key = crypto.getRandomValues(new Uint8Array(32));
    await expect(encryptOpaqueRecoveryEnvelope({ accountId: "bad", opaqueRecordId: "rec_1234567890123456", exportKeyBase64Url: "AA", recoveryKey: key })).rejects.toThrow();
    await expect(encryptOpaqueRecoveryEnvelope({ accountId: "uid-1", opaqueRecordId: "short", exportKeyBase64Url: "AA", recoveryKey: key })).rejects.toThrow();
    await expect(decryptOpaqueRecoveryEnvelope({ accountId: "uid-1", exportKeyBase64Url: "!!", envelope: { version: 1, accountId: "uid-2", opaqueRecordId: "rec_1234567890123456", algorithm: "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM", salt: "A".repeat(44), iv: "A".repeat(16), ciphertext: "A".repeat(64) } })).rejects.toThrow();

    for (const [code, message, expected] of [
      ["functions/resource-exhausted", "limit", /Wait 15 minutes/],
      ["functions/permission-denied", "access revoked", /revoked/],
      ["functions/permission-denied", "bad pw", /Incorrect recovery password/],
      ["functions/not-found", "missing", /not configured/],
      ["functions/unauthenticated", "auth", /Sign in again/],
      ["functions/failed-precondition", "Please sign in again", /Sign in again/],
      ["functions/unavailable", "net", /Connection interrupted/],
    ] as const) {
      const flow = createOpaqueRecoveryFlow(async () => {
        throw Object.assign(new Error(message), { code });
      });
      await expect(flow.register("uid-1", "Password1!", key)).rejects.toThrow(expected);
      await expect(flow.unlock("uid-1", "Password1!")).rejects.toThrow(expected);
    }
  });
});
