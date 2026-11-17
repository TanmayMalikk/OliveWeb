import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { issueEntryLink, savedEntryLink, tokenDigest, verifyEntryLink } from "./PrivateEntryLinks";

const state = vi.hoisted(() => ({
  uid: "owner",
  remote: new Map<string, { tokenHash: string }>(),
  local: new Map<string, string>(),
  covers: new Map<string, string>(),
}));
vi.mock("./AccountService", () => ({ cloudMode: true, firebase: async () => ({ db: {}, auth: { currentUser: { uid: state.uid } } }) }));
vi.mock("./LocalDataStore", () => ({ database: Promise.resolve({
  put: (_store: string, value: string, key: string) => state.local.set(key, value),
  get: (_store: string, key: string) => state.local.get(key),
}) }));
vi.mock("./CoverStateStore", () => ({
  writeCover: (key: string, value: string) => state.covers.set(key, value),
  readCover: (key: string, fallback: string | null) => state.covers.get(key) ?? fallback,
}));
vi.mock("firebase/firestore", () => ({
  doc: (_db: unknown, collection: string, uid: string) => collection + "/" + uid,
  serverTimestamp: () => 1,
  setDoc: (path: string, value: { tokenHash: string }) => state.remote.set(path, value),
  getDoc: (path: string) => ({ data: () => state.remote.get(path) }),
}));
beforeEach(() => {
  state.uid = "owner";
  state.remote.clear();
  state.local.clear();
  state.covers.clear();
  vi.stubGlobal("location", { origin: "https://example.invalid" });
});
afterEach(() => vi.unstubAllGlobals());

it("rotates private entry links and syncs only the active token hash to cloud", async () => {
  expect(await savedEntryLink("owner")).toBeNull();
  const first = await issueEntryLink("owner");
  const token = state.covers.get("owner:personal-link")!;
  expect(first).toContain(token);
  expect(state.remote.get("webEntryLinks/owner")?.tokenHash).toBe(await tokenDigest(token));
  expect(state.remote.get("webEntryLinks/owner")?.tokenHash).not.toBe(token);
  expect(await savedEntryLink("owner")).toBe(first);
  const second = await issueEntryLink("owner");
  expect(second).not.toBe(first);
  expect(await verifyEntryLink("owner", token)).toBe(false);
});

it("binds verification to the authenticated account and checks local-only links without a cloud lookup", async () => {
  await issueEntryLink("owner");
  const token = state.covers.get("owner:personal-link")!;
  state.local.clear();
  expect(await verifyEntryLink("owner", token)).toBe(true);
  expect(state.local.get("entry-hash:owner")).toBe(await tokenDigest(token));
  state.uid = "other";
  expect(await verifyEntryLink("owner", token)).toBe(false);
  expect(await verifyEntryLink("owner", token, true)).toBe(true);
  await expect(issueEntryLink("owner")).rejects.toThrow("Sign in again");
});
