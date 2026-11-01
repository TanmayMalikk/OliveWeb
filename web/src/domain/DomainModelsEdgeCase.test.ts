// Tests web account, cover, history, and error-model boundaries added during recent app changes.
import { describe, expect, it } from "vitest";
import {
  covers,
  coverInfo,
  coverPath,
  emptyHistory,
  emptyProfile,
  friendlyError,
  newEvent,
  normalizeCover,
  normalizeUsername,
  purgeEntry,
  signInEmail,
  TRASH_RETENTION_MS,
  validPassword,
  validPin,
  validUsername,
  type Entry,
} from "./DomainModels";

describe("recent web domain boundaries", () => {
  // Input: every published cover. Expected: IDs, slugs, paths, and metadata remain unique and resolvable.
  it("keeps every cover identity unique and routable", () => {
    expect(new Set(covers.map((cover) => cover.id)).size).toBe(covers.length);
    expect(new Set(covers.map((cover) => cover.slug)).size).toBe(covers.length);
    expect(new Set(covers.map((cover) => coverPath(cover.id))).size).toBe(covers.length);
    for (const cover of covers) expect(coverInfo(cover.id)).toEqual(cover);
  });

  // Input: the removed calculator cover and an invalid saved value. Expected: old accounts migrate safely without crashing the public page.
  it("migrates removed and invalid saved cover values", () => {
    expect(normalizeCover("CALCULATOR")).toBe("FITNESS");
    expect(normalizeCover("FITNESS")).toBe("FITNESS");
    expect(normalizeCover("unknown-cover")).toBe("GAME");
    expect(normalizeCover(null)).toBe("GAME");
  });

  // Input: exact username boundaries and non-letter lookalikes. Expected: web validation matches Android and Cloud rules.
  it("requires a letter at exact username length boundaries", () => {
    expect(validUsername("a1234567")).toBe(true);
    expect(validUsername(`a${"1".repeat(23)}`)).toBe(true);
    expect(validUsername("a123456")).toBe(false);
    expect(validUsername(`a${"1".repeat(24)}`)).toBe(false);
    expect(validUsername("12345678")).toBe(false);
    expect(validUsername("________")).toBe(false);
  });

  // Input: mixed case, whitespace, Unicode, punctuation, and SQL-like text. Expected: normalization cannot widen allowed syntax.
  it("normalizes ordinary usernames but rejects hostile forms", () => {
    expect(normalizeUsername("  Olive_User  ")).toBe("olive_user");
    for (const value of ["olive-user", "olivé_user", "' OR 1=1", "olive user", "🫒olive_user"])
      expect(validUsername(value)).toBe(false);
  });

  // Input: Unicode letters plus number and symbol, then missing requirement variants. Expected: password policy is Unicode-safe.
  it("supports Unicode passwords without dropping requirements", () => {
    expect(validPassword("Étoile12!")).toBe(true);
    expect(validPassword("橄榄枝1234!")).toBe(true);
    expect(validPassword("橄榄!!!!")).toBe(false);
    expect(validPassword("1234567!")).toBe(false);
    expect(validPassword("Olive 123")).toBe(false);
  });

  // Input: 3–7 digits, signs, decimals, spaces, and Unicode digits. Expected: only 4–6 ASCII digits are accepted.
  it("accepts only the documented PIN shapes", () => {
    expect(validPin("1234")).toBe(true);
    expect(validPin("12345")).toBe(true);
    expect(validPin("123456")).toBe(true);
    for (const value of ["123", "1234567", "+1234", "12.34", " 1234", "１２３４"])
      expect(validPin(value)).toBe(false);
  });

  // Input: every account role and organization email casing. Expected: aliases match mobile authentication exactly.
  it("creates deterministic sign-in identifiers for every role", () => {
    expect(signInEmail(" Olive_User ", "USER")).toBe("olive_user@users.olive.invalid");
    expect(signInEmail(" Olive_User ", "SAFE_CONTACT")).toBe("olive_user@safe.olive.invalid");
    expect(signInEmail(" Staff@Example.CA ", "STAFF")).toBe("staff@example.ca");
    expect(signInEmail(" Admin@Example.CA ", "ADMIN")).toBe("admin@example.ca");
  });

  // Input: two new histories and profiles. Expected: mutable arrays and nested objects are never shared between accounts.
  it("creates independent empty account state", () => {
    const firstHistory = emptyHistory();
    const secondHistory = emptyHistory();
    firstHistory.settings.push(newEvent("changed"));
    expect(secondHistory.settings).toEqual([]);
    const firstProfile = emptyProfile("first");
    const secondProfile = emptyProfile("second", "SAFE_CONTACT");
    firstProfile.contacts.push({ id: "1", name: "Friend", details: "555" });
    firstProfile.address.city = "Edmonton";
    expect(secondProfile.contacts).toEqual([]);
    expect(secondProfile.address.city).toBe("");
    expect(secondProfile.role).toBe("SAFE_CONTACT");
  });

  // Input: active, boundary-expired, and already-purged trash entries. Expected: redaction is exact and idempotent.
  it("purges trash exactly once at the retention boundary", () => {
    const entry: Entry = {
      id: "note", timestamp: 1, updatedAt: 1, kind: "Note", title: "Private", body: "Secret",
      pinned: true, edits: [{ id: "edit", timestamp: 2, changes: [] }], deletedAt: 100,
    };
    expect(purgeEntry(entry, 100 + TRASH_RETENTION_MS - 1)).toBe(entry);
    const purged = purgeEntry(entry, 100 + TRASH_RETENTION_MS);
    expect(purged).toMatchObject({ title: "Deleted entry", body: "", edits: [], pinned: true });
    expect(purgeEntry(purged, 100 + TRASH_RETENTION_MS + 500)).toEqual(purged);
  });

  // Input: known auth failures, unknown auth failures, Error objects, and scalars. Expected: users get stable safe messages.
  it("maps authentication and unknown failures to safe messages", () => {
    expect(friendlyError({ code: "auth/network-request-failed" })).toContain("connection");
    expect(friendlyError({ code: "auth/new-future-code" })).toContain("Unable to sign in");
    expect(friendlyError(new Error("Local failure"))).toBe("Local failure");
    expect(friendlyError("raw failure")).toBe("Something went wrong. Please try again.");
  });

  // Input: consecutive history events. Expected: identifiers differ and each event owns a fresh changes array.
  it("creates independent uniquely identified history events", () => {
    const first = newEvent("save", "Settings");
    const second = newEvent("save", "Settings");
    expect(first.id).not.toBe(second.id);
    first.changes?.push({ field: "city", before: "A", after: "B" });
    expect(second.changes).toEqual([]);
    expect(first.timestamp).toBeGreaterThan(0);
  });
});
