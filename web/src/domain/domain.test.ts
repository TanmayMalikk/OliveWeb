// Tests account and cover rules for all app roles.
import { describe, it, expect } from "vitest";
import {
  signInEmail,
  validUsername,
  validPassword,
  validPin,
  coverPath,
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
  it("keeps the existing role-specific authentication aliases", () => {
    expect(signInEmail(" Alice_User ", "USER")).toBe(
      "alice_user@users.olive.invalid",
    );
    expect(signInEmail("alice_user", "SAFE_CONTACT")).toBe(
      "alice_user@safe.olive.invalid",
    );
    expect(signInEmail("Staff@Example.com", "STAFF")).toBe("staff@example.com");
  });
  it("matches mobile credential limits", () => {
    expect(validUsername("short")).toBe(false);
    expect(validUsername("olive_user")).toBe(true);
    expect(validPassword("password123")).toBe(false);
    expect(validPassword("Test-password1!")).toBe(true);
    expect(validPin("123456")).toBe(true);
    expect(validPin("1234567")).toBe(false);
  });
  it("routes each cover and irreversibly redacts expired trash copies", () => {
    expect(coverPath("NEWS")).toBe("/daily-digest");
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
  it("uses the mobile trigger normalization", async () => {
    expect(await triggerHash(" Moon Garden ")).toBe(
      await triggerHash("moongarden"),
    );
  });
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
  it("requires the correct password or PIN to unwrap an account key", async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32));
    const wrapped = await wrapKey("test-pass1!", raw);
    expect(await unwrapKey("test-pass1!", wrapped)).toEqual(raw);
    await expect(unwrapKey("wrong-pass1!", wrapped)).rejects.toThrow();
  });
});
