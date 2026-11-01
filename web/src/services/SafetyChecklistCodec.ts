export type SafetyChecklistItem = {
  id: string;
  text: string;
  checked: boolean;
};

// Web safety plans: decodes the checklist format shared with the Android app.
export function readSafetyChecklist(value: string): SafetyChecklistItem[] {
  // If read safety checklist fails, catch the error so the app can keep running and explain the problem.
  try {
    const parsed = JSON.parse(value) as {
      oliveSafetyChecklist?: unknown;
      items?: unknown;
    };
    if (parsed.oliveSafetyChecklist !== 1 || !Array.isArray(parsed.items))
      throw new Error("Not a safety checklist");
    const items = parsed.items.slice(0, 80).map((item) => {
      if (!item || typeof item !== "object") throw new Error("Invalid checklist row");
      const row = item as Record<string, unknown>;
      if (
        typeof row.id !== "string" ||
        typeof row.text !== "string" ||
        typeof row.checked !== "boolean"
      )
        throw new Error("Invalid checklist row");
      return { id: row.id, text: row.text, checked: row.checked };
    });
    if (new Set(items.map((item) => item.id)).size !== items.length)
      throw new Error("Duplicate checklist row");
    return items;
  } catch {
    return value.trim()
      ? [{ id: crypto.randomUUID(), text: value, checked: false }]
      : [];
  }
}

// Web safety plans: writes the exact checklist structure expected by Android.
export function writeSafetyChecklist(items: SafetyChecklistItem[]) {
  return JSON.stringify({ oliveSafetyChecklist: 1, items: items.slice(0, 80) });
}

// Web safety plans: makes linked contact rows readable without exposing their JSON storage format.
export function safetyChecklistText(item: SafetyChecklistItem) {
  if (!item.id.startsWith("trusted-contact:")) return item.text;
  // A safety checklist text error is handled here instead of closing the app.
  try {
    const contact = JSON.parse(item.text) as { name?: unknown; details?: unknown };
    if (typeof contact.name !== "string" || typeof contact.details !== "string")
      return item.text;
    return [contact.name, contact.details].filter(Boolean).join(": ");
  } catch {
    return item.text;
  }
}
