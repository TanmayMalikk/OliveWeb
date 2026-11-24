// Form feedback tests: verifies CSS shake class triggering, repeat retries, and field invalid styling helpers.
import { parseHTML } from "linkedom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  clearFieldInvalid,
  resolveShakeContainer,
  shakeFormElement,
  shakeNamedFields,
  shouldTriggerControlledFieldShake,
} from "./FormShake";

describe("FormShake helpers", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("applies field-invalid and field-shake on the enclosing field container and clears shake after timeout", () => {
    const { document } = parseHTML(`
      <form>
        <div class="field" id="wrap">
          <label for="u">Username</label>
          <span class="input-wrap"><input id="u" name="username" required /></span>
        </div>
      </form>
    `);
    const input = document.getElementById("u") as unknown as Element;
    const wrap = document.getElementById("wrap") as unknown as HTMLElement;

    expect(resolveShakeContainer(input)).toBe(wrap);

    shakeFormElement(input);
    expect(wrap.classList.contains("field-invalid")).toBe(true);
    expect(wrap.classList.contains("field-shake")).toBe(true);

    // Timeout cleanup removes transient .field-shake while preserving .field-invalid until edited.
    vi.advanceTimersByTime(550);
    expect(wrap.classList.contains("field-shake")).toBe(false);
    expect(wrap.classList.contains("field-invalid")).toBe(true);

    // Clearing removes .field-invalid once the user starts typing.
    clearFieldInvalid(input);
    expect(wrap.classList.contains("field-invalid")).toBe(false);
  });

  it("shakes multiple named controls including hidden dob inputs via their birthday fieldset and focuses first invalid", () => {
    const { document } = parseHTML(`
      <form id="f">
        <div class="field" id="name-wrap"><input id="name-input" name="fullName" /></div>
        <fieldset class="birthday-field" id="dob-wrap">
          <button type="button" id="dob-btn">Choose date of birth</button>
          <input type="hidden" name="dob" value="" />
        </fieldset>
        <div class="field" id="city-wrap"><input id="city-input" name="city" /></div>
      </form>
    `);
    const form = document.getElementById("f") as unknown as HTMLFormElement;
    const nameInput = document.getElementById("name-input") as unknown as HTMLElement;
    const dobBtn = document.getElementById("dob-btn") as unknown as HTMLElement;
    const nameFocus = vi.spyOn(nameInput, "focus");
    const dobFocus = vi.spyOn(dobBtn, "focus");

    // When both dob and city fail, hidden dob should resolve its button to receive focus.
    shakeNamedFields(form, ["dob", "city"]);
    expect(document.getElementById("dob-wrap")!.classList.contains("field-invalid")).toBe(true);
    expect(document.getElementById("dob-wrap")!.classList.contains("field-shake")).toBe(true);
    expect(document.getElementById("city-wrap")!.classList.contains("field-shake")).toBe(true);
    expect(document.getElementById("name-wrap")!.classList.contains("field-shake")).toBe(false);
    expect(dobFocus).toHaveBeenCalledTimes(1);

    // When fullName is included, fullName (the first named control) receives focus.
    shakeNamedFields(form, ["fullName"]);
    expect(nameFocus).toHaveBeenCalledTimes(1);
  });

  it("re-triggers controlled field shakes on initial error, changed error, and repeated submit attempts", () => {
    expect(shouldTriggerControlledFieldShake(undefined, undefined, 0, 0)).toBe(false);
    expect(shouldTriggerControlledFieldShake("Invalid PIN", "Invalid PIN", 1, 1)).toBe(false);
    expect(shouldTriggerControlledFieldShake(undefined, "Invalid PIN", 0, 0)).toBe(true);
    expect(shouldTriggerControlledFieldShake("Invalid PIN", "PINs do not match", 1, 1)).toBe(true);
    expect(shouldTriggerControlledFieldShake("Invalid PIN", "Invalid PIN", 1, 2)).toBe(true);
    expect(shouldTriggerControlledFieldShake("Invalid PIN", undefined, 2, 3)).toBe(false);
  });
});
