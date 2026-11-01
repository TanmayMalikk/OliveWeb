// Tests web discreet-entry tokens at format boundaries and against malformed runtime values.
import { describe, expect, it } from "vitest";
import { tokenDigest, validEntryToken } from "./EntryTokenPolicy";

describe("entry token policy", () => {
  // Inputs: 42, 43, and 44-character tokens. Expected: only the exact 43-character boundary is accepted.
  it("requires exactly 43 URL-safe characters", () => {
    expect(validEntryToken("a".repeat(42))).toBe(false);
    expect(validEntryToken("a".repeat(43))).toBe(true);
    expect(validEntryToken("a".repeat(44))).toBe(false);
    expect(validEntryToken(`${"a".repeat(41)}-_`)).toBe(true);
  });

  // Inputs: spaces, punctuation, Unicode, line breaks, and wrong runtime types. Expected: none are treated as valid entry tokens.
  it("rejects unsafe characters and incorrect variable types", () => {
    for (const input of [
      `${"a".repeat(42)}=`,
      `${"a".repeat(42)} `,
      `${"a".repeat(42)}\n`,
      `${"a".repeat(42)}é`,
      null,
      undefined,
      1234,
      {},
      [],
    ]) {
      expect(validEntryToken(input as string)).toBe(false);
    }
  });

  // Inputs: the same token twice and a one-character mutation. Expected: hashing is stable, fixed-length, and changes with the input.
  it("creates deterministic distinct SHA-256 digests", async () => {
    const first = "a".repeat(43);
    const second = `${"a".repeat(42)}b`;
    const firstDigest = await tokenDigest(first);

    expect(firstDigest).toHaveLength(64);
    expect(firstDigest).toMatch(/^[0-9a-f]{64}$/);
    expect(await tokenDigest(first)).toBe(firstDigest);
    expect(await tokenDigest(second)).not.toBe(firstDigest);
  });
});
