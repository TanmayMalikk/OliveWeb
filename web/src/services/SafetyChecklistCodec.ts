export type SafetyChecklistItem = {
  id: string;
  text: string;
  checked: boolean;
  attachmentIds?: string[];
  excludedAttachmentIds?: string[];
};

export type SafetyChecklistConflictPreview = {
  item: string;
  property: string;
  value: string;
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
      ? [{ id: "legacy-note", text: value, checked: false }]
      : [];
  }
}

// Returns only checklist rows and properties whose owner/incoming values disagree.
export function safetyChecklistConflictPreview(
  mine: string, incoming: string, showMine: boolean,
): SafetyChecklistConflictPreview[] | null {
  const isStructured = (value: string) => {
    try {
      return (JSON.parse(value) as { oliveSafetyChecklist?: unknown }).oliveSafetyChecklist === 1;
    } catch {
      return false;
    }
  };
  if (!isStructured(mine) || !isStructured(incoming)) return null;

  const mineById = new Map(readSafetyChecklist(mine).map((item) => [item.id, item]));
  const incomingById = new Map(readSafetyChecklist(incoming).map((item) => [item.id, item]));
  const preview: SafetyChecklistConflictPreview[] = [];
  for (const id of new Set([...mineById.keys(), ...incomingById.keys()])) {
    const yourItem = mineById.get(id);
    const incomingItem = incomingById.get(id);
    if (JSON.stringify(yourItem) === JSON.stringify(incomingItem)) continue;
    if (!yourItem || !incomingItem) {
      const item = yourItem ?? incomingItem;
      if (!item) continue;
      preview.push({
        item: item.text,
        property: "Step",
        value: showMine ? (yourItem ? "Present" : "Removed") : (incomingItem ? "Present" : "Removed"),
      });
      continue;
    }
    const itemText = showMine ? yourItem.text : incomingItem.text;
    if (yourItem.text !== incomingItem.text) {
      preview.push({ item: itemText, property: "Text", value: itemText });
    }
    if (yourItem.checked !== incomingItem.checked) {
      preview.push({
        item: itemText,
        property: "Completion",
        value: (showMine ? yourItem.checked : incomingItem.checked) ? "Complete" : "Not complete",
      });
    }
    if (JSON.stringify(yourItem.attachmentIds ?? []) !== JSON.stringify(incomingItem.attachmentIds ?? [])) {
      const count = (showMine ? yourItem.attachmentIds : incomingItem.attachmentIds)?.length ?? 0;
      preview.push({ item: itemText, property: "Attachments", value: `${count} attachment${count === 1 ? "" : "s"}` });
    }
    if (JSON.stringify(yourItem.excludedAttachmentIds ?? []) !== JSON.stringify(incomingItem.excludedAttachmentIds ?? [])) {
      const count = (showMine ? yourItem.excludedAttachmentIds : incomingItem.excludedAttachmentIds)?.length ?? 0;
      preview.push({ item: itemText, property: "Excluded attachments", value: `${count} excluded attachment${count === 1 ? "" : "s"}` });
    }
  }
  return preview;
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
