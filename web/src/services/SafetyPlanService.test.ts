import { describe, expect, it } from "vitest";
import {
  readSafetyChecklist,
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
});
