// Messaging tests: checks that Android sharing codes become readable web attachment cards.
import { describe, expect, it } from "vitest";
import { sharedMessage } from "./SharedMessageContent";

describe("shared chat attachments", () => {
  it("recognizes each supported shared item without changing normal messages", () => {
    expect(sharedMessage("SHARED_MEDIA:file-1:Olive photo.jpg")).toEqual({
      kind: "media",
      id: "file-1",
      title: "Olive photo.jpg",
    });
    expect(sharedMessage("SHARED_INCIDENT:incident-1:Kitchen incident")).toEqual({
      kind: "incident",
      id: "incident-1",
      title: "Kitchen incident",
    });
    expect(sharedMessage("SHARED_NOTE:note-1:Private note")).toEqual({
      kind: "note",
      id: "note-1",
      title: "Private note",
    });
    expect(sharedMessage("SHARED_SAFETY_PLAN:user-1:Safety plan")).toEqual({
      kind: "safety-plan",
      title: "Safety plan",
    });
    expect(sharedMessage("hello there")).toBeNull();
  });

  it("rejects an incomplete media code instead of showing a broken Open button", () => {
    expect(sharedMessage("SHARED_MEDIA")).toBeNull();
  });
});
