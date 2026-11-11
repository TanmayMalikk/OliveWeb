import { describe, expect, it } from "vitest";
import {
  readSafetyChecklist,
  safetyChecklistConflictPreview,
  safetyChecklistText,
  writeSafetyChecklist,
} from "./SafetyChecklistCodec";

describe("web and Android safety checklist compatibility", () => {
  it("round-trips the structured checklist without displaying its JSON", () => {
    const original = [
      { id: "destination-1", text: "Choose a safe place", checked: true },
      { id: "travel-1", text: "Plan the route", checked: false },
    ];

    expect(readSafetyChecklist(writeSafetyChecklist(original))).toEqual(original);
  });

  it("keeps Android document links and exclusions when a web user edits a step", () => {
    const [step] = readSafetyChecklist(JSON.stringify({
      oliveSafetyChecklist: 1,
      items: [{
        id: "document-step",
        text: "Find my passport",
        checked: false,
        attachmentIds: ["passport-id"],
        excludedAttachmentIds: ["removed-id"],
      }],
    }));

    expect(readSafetyChecklist(writeSafetyChecklist([{ ...step, checked: true }]))).toEqual([{
      id: "document-step",
      text: "Find my passport",
      checked: true,
      attachmentIds: ["passport-id"],
      excludedAttachmentIds: ["removed-id"],
    }]);
  });

  it("keeps an older plain-text safety-plan value as one editable step", () => {
    expect(readSafetyChecklist("Call my trusted contact")).toEqual([
      expect.objectContaining({ text: "Call my trusted contact", checked: false }),
    ]);
  });

  it("renders a linked trusted contact as readable text", () => {
    expect(
      safetyChecklistText({
        id: "trusted-contact:abc",
        text: JSON.stringify({ name: "Alex", details: "555-0100" }),
        checked: false,
      }),
    ).toBe("Alex: 555-0100");
  });

  it("shows only conflicting checklist properties in each resolution choice", () => {
    const mine = writeSafetyChecklist([
      { id: "same-row", text: "My wording", checked: false },
      { id: "other-row", text: "Unrelated", checked: true },
    ]);
    const incoming = writeSafetyChecklist([
      { id: "same-row", text: "Incoming wording", checked: false },
      { id: "other-row", text: "Unrelated", checked: true },
    ]);

    expect(safetyChecklistConflictPreview(mine, incoming, true)).toEqual([
      { item: "My wording", property: "Text", value: "My wording" },
    ]);
    expect(safetyChecklistConflictPreview(mine, incoming, false)).toEqual([
      { item: "Incoming wording", property: "Text", value: "Incoming wording" },
    ]);
    expect(safetyChecklistConflictPreview("mine", "incoming", true)).toBeNull();
  });
});
