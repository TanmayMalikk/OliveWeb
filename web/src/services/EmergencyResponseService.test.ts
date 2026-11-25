import { beforeEach, expect, it, vi } from "vitest";
import {
  parseAdminLiveLocation,
  parseEmergencyReport,
  setEmergencyResolved,
} from "./EmergencyResponseService";

const mocks = vi.hoisted(() => ({ callable: vi.fn() }));
vi.mock("./AccountService", () => ({
  firebase: async () => ({ db: {}, rtdb: {}, functions: {} }),
}));
vi.mock("./MediaService", () => ({
  loadBrowserDocumentById: vi.fn(),
  openBrowserDocument: vi.fn(),
}));
vi.mock("firebase/firestore", () => ({
  collection: vi.fn(),
  onSnapshot: vi.fn(),
}));
vi.mock("firebase/database", () => ({
  ref: vi.fn(),
  onValue: vi.fn(),
}));
vi.mock("firebase/functions", () => ({
  httpsCallable: (_functions: unknown, name: string) => (payload: unknown) => mocks.callable(name, payload),
}));

beforeEach(() => {
  vi.resetAllMocks();
});

it("parses Firestore emergency reports with optional resolved timestamps", () => {
  expect(parseEmergencyReport("em-1", {
    ownerUid: "user-1",
    ownerName: "alice",
    createdAt: { toMillis: () => 1_700_000_000_000 },
    mediaId: "pdf-1",
    status: "ready",
  })).toEqual({
    id: "em-1",
    ownerUid: "user-1",
    ownerName: "alice",
    createdAt: 1_700_000_000_000,
    mediaId: "pdf-1",
    status: "ready",
    resolvedAt: 0,
    resolvedBy: "",
  });

  expect(parseEmergencyReport("em-bad", { ownerUid: "" })).toBeNull();
});

it("validates coordinates on admin live location points", () => {
  expect(parseAdminLiveLocation("user-1", {
    name: "Alice",
    latitude: 53.5461,
    longitude: -113.4938,
    accuracy: 12,
    updatedAt: 1_700_000_000_000,
    active: true,
  })).toEqual({
    uid: "user-1",
    name: "Alice",
    latitude: 53.5461,
    longitude: -113.4938,
    accuracy: 12,
    updatedAt: 1_700_000_000_000,
    active: true,
  });

  expect(parseAdminLiveLocation("user-bad", {
    latitude: 95,
    longitude: -113,
    updatedAt: 1000,
  })).toBeNull();
});

it("calls emergencyResolve and emergencyReopen callables", async () => {
  mocks.callable.mockResolvedValue({ data: { resolved: true } });
  await setEmergencyResolved("em-1", true);
  await setEmergencyResolved("em-1", false);
  expect(mocks.callable.mock.calls).toEqual([
    ["emergencyResolve", { id: "em-1" }],
    ["emergencyReopen", { id: "em-1" }],
  ]);
});
