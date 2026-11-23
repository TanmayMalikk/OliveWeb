// All app roles: triggers and clears accessible field shake + invalid highlight feedback on failed attempts.

const FORM_FIELD_SELECTOR = ".field, .birthday-field, .checkbox-field, .pin-dots";
const SHAKE_CLASS = "field-shake";
const INVALID_CLASS = "field-invalid";
const SHAKE_TIMEOUT_MS = 500;

const activeShakeTimers = new WeakMap<Element, ReturnType<typeof setTimeout>>();

/** Resolves the visual container element that owns the shake/error styling for a control. */
export function resolveShakeContainer(element: Element | null): HTMLElement | null {
  if (!element) return null;
  return (element.closest(FORM_FIELD_SELECTOR) ?? element) as HTMLElement;
}

/** Applies the invalid highlight and restarts the damped horizontal shake animation on an element. */
export function shakeFormElement(element: Element | null) {
  const target = resolveShakeContainer(element);
  if (!target) return;
  const previousTimer = activeShakeTimers.get(target);
  if (previousTimer !== undefined) {
    clearTimeout(previousTimer);
  }
  target.classList.add(INVALID_CLASS);
  target.classList.remove(SHAKE_CLASS);
  // Force style/layout flush so re-adding .field-shake restarts the CSS animation cleanly.
  void (target as { offsetWidth?: number }).offsetWidth;
  target.classList.add(SHAKE_CLASS);

  const finish = () => {
    target.classList.remove(SHAKE_CLASS);
    const active = activeShakeTimers.get(target);
    if (active !== undefined) {
      clearTimeout(active);
      activeShakeTimers.delete(target);
    }
    target.removeEventListener("animationend", finish);
  };
  target.addEventListener("animationend", finish, { once: true });
  activeShakeTimers.set(target, setTimeout(finish, SHAKE_TIMEOUT_MS));
}

/** Clears the persistent invalid highlight when a user begins correcting a field. */
export function clearFieldInvalid(element: Element | null) {
  resolveShakeContainer(element)?.classList.remove(INVALID_CLASS);
}

/** Finds a focusable control inside a form container, falling back to the birthday trigger button for hidden inputs. */
function focusTargetFor(control: Element, container: HTMLElement | null): HTMLElement | null {
  if (control.getAttribute("type")?.toLowerCase() === "hidden") {
    return container?.querySelector("button, input:not([type='hidden']), select, textarea") as HTMLElement | null;
  }
  return control as HTMLElement;
}

/** Shakes and marks invalid every control in `form` whose `name` appears in `names`, then focuses the first one. */
export function shakeNamedFields(form: HTMLFormElement | null, names: string[]) {
  if (!form || names.length === 0) return;
  const wanted = new Set(names);
  const controls = Array.from(form.querySelectorAll("[name]")).filter((control) =>
    wanted.has(control.getAttribute("name") ?? ""),
  );
  let firstFocusable: HTMLElement | null = null;
  for (const control of controls) {
    const container = resolveShakeContainer(control);
    shakeFormElement(control);
    if (!firstFocusable) {
      firstFocusable = focusTargetFor(control, container);
    }
  }
  firstFocusable?.focus();
}

/** Returns true when a controlled React field should shake due to new/changed error text or a repeated submit trigger. */
export function shouldTriggerControlledFieldShake(
  previousError: string | undefined,
  currentError: string | undefined,
  previousTrigger: number,
  currentTrigger: number,
): boolean {
  return Boolean(currentError) && (currentError !== previousError || currentTrigger > previousTrigger);
}
