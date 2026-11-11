export type SafetyChecklistItem = {
  id: string;
  text: string;
  checked: boolean;
  attachmentIds?: string[];
  excludedAttachmentIds?: string[];
};

function documentIds(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return [...new Set(value.filter((id): id is string =>
    typeof id === "string" && id.length > 0 && id.length <= 128,
  ))].slice(0, 80);
}

// Web safety plans: decodes the checklist format shared with the Android app.
export function readSafetyChecklist(value: string): SafetyChecklistItem[] {
  // Turn an unexpected read safety checklist failure into a readable message.
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
      const attachmentIds = documentIds(row.attachmentIds);
      const excludedAttachmentIds = documentIds(row.excludedAttachmentIds);
      return {
        id: row.id,
        text: row.text,
        checked: row.checked,
        ...(attachmentIds?.length ? { attachmentIds } : {}),
        ...(excludedAttachmentIds?.length ? { excludedAttachmentIds } : {}),
      };
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
  // Use the fallback when safety checklist text is unavailable.
  try {
    const contact = JSON.parse(item.text) as { name?: unknown; details?: unknown };
    if (typeof contact.name !== "string" || typeof contact.details !== "string")
      return item.text;
    return [contact.name, contact.details].filter(Boolean).join(": ");
  } catch {
    return item.text;
  }
}
