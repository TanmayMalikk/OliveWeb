// Account sync tests: checks that one bad cloud file cannot stop a user from signing in.
import { describe, expect, it } from "vitest";
import { readStoredObject } from "./StoredAccountJson";

const bytes = (value: string) => new TextEncoder().encode(value);

describe("stored account JSON", () => {
  it("reads a normal saved record", () => {
    expect(readStoredObject(bytes('{"id":"note-1","title":"Safe"}'))).toEqual({
      id: "note-1",
      title: "Safe",
    });
  });

  it("repairs the trailing comma left by an interrupted older save", () => {
    expect(readStoredObject(bytes('{"id":"note-1",}'))).toEqual({ id: "note-1" });
  });

  it("returns null for a truncated or wrong-shaped record", () => {
    expect(readStoredObject(bytes('{"id":"unfinished'))).toBeNull();
    expect(readStoredObject(bytes('["not","an","object"]'))).toBeNull();
  });
});
