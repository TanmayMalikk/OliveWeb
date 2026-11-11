// Tests account and cover rules for all app roles.
import { describe, it, expect } from "vitest";
import {
  signInEmail,
  validUsername,
  validPassword,
  validPin,
  purgeEntry,
  TRASH_RETENTION_MS,
  type Entry,
} from "./DomainModels";
import {
  encrypt,
  decrypt,
  importKey,
  wrapKey,
  unwrapKey,
  triggerHash,
} from "../services/EncryptionService";
describe("mobile-compatible contracts", () => {
  // Inputs: the values in this case. Expected: keeps the existing role-specific authentication aliases.
  it("keeps the existing role-specific authentication aliases", () => {
    expect(signInEmail(" Alice_User ", "USER")).toBe(
      "alice_user@users.olive.invalid",
    );
    expect(signInEmail("alice_user", "SAFE_CONTACT")).toBe(
      "alice_user@safe.olive.invalid",
    );
    expect(signInEmail("Staff@Example.com", "STAFF")).toBe("staff@example.com");
    expect(signInEmail("staff_user", "STAFF")).toBe(
      "staff_user@yourolivebranch.ca",
    );
  });
  // Inputs: the values in this case. Expected: matches mobile credential limits.
  it("matches mobile credential limits", () => {
    expect(validUsername("short")).toBe(false);
    expect(validUsername("olive_user")).toBe(true);
    expect(validPassword("password123")).toBe(false);
    expect(validPassword("Test-password1!")).toBe(true);
    expect(validPin("123456")).toBe(true);
    expect(validPin("1234567")).toBe(false);
  });
  // Inputs: SQL-like strings in every credential field. Expected: browser validation rejects them as ordinary invalid text.
  it("rejects injection-like credential values", () => {
    const injectionText = "' OR 1=1; DROP TABLE users; --";
    expect(validUsername(injectionText)).toBe(false);
    expect(validPassword(injectionText)).toBe(false);
    expect(validPin(injectionText)).toBe(false);
  });
  // Inputs: empty strings and strings far beyond field limits. Expected: boundary validation rejects all oversized values.
  it("rejects empty and oversized browser credentials", () => {
    expect(validUsername("")).toBe(false);
    expect(validUsername("a".repeat(100_000))).toBe(false);
    expect(validPassword(`A1!${"x".repeat(100_000)}`)).toBe(false);
    expect(validPin("9".repeat(100_000))).toBe(false);
  });
  // Inputs: the values in this case. Expected: irreversibly redacts expired trash copies.
  it("irreversibly redacts expired trash copies", () => {
    const entry: Entry = {
      id: "id",
      timestamp: 1,
      updatedAt: 1,
      title: "Secret",
      body: "Private",
      kind: "Note",
      pinned: false,
      edits: [],
      deletedAt: 1000,
    };
    expect(purgeEntry(entry, 1000 + TRASH_RETENTION_MS - 1).body).toBe(
      "Private",
    );
    expect(purgeEntry(entry, 1000 + TRASH_RETENTION_MS)).toMatchObject({
      title: "Deleted entry",
      body: "",
      edits: [],
    });
  });
});
describe("encrypted browser vault", () => {
  // Inputs: the values in this case. Expected: uses the mobile trigger normalization.
  it("uses the mobile trigger normalization", async () => {
    expect(await triggerHash(" Moon Garden ")).toBe(
      await triggerHash("moongarden"),
    );
  });
  // Inputs: the values in this case. Expected: uses randomized ciphertext and rejects changed or wrong-key data.
  it("uses randomized ciphertext and rejects changed or wrong-key data", async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32));
    const key = await importKey(raw);
    const first = await encrypt(key, { body: "private fixture" });
    const second = await encrypt(key, { body: "private fixture" });
    expect(first.data).not.toBe(second.data);
    expect(first.data).not.toContain("private fixture");
    expect(await decrypt(key, first)).toEqual({ body: "private fixture" });
    await expect(
      decrypt(
        await importKey(crypto.getRandomValues(new Uint8Array(32))),
        first,
      ),
    ).rejects.toThrow();
  });
  // Inputs: the values in this case. Expected: requires the correct password or PIN to unwrap an account key.
  it("requires the correct password or PIN to unwrap an account key", async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32));
    const wrapped = await wrapKey("test-pass1!", raw);
    expect(await unwrapKey("test-pass1!", wrapped)).toEqual(raw);
    await expect(unwrapKey("wrong-pass1!", wrapped)).rejects.toThrow();
  });
});
