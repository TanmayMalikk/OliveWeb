// Account sync: reads one saved JSON file without letting a damaged file break the whole account.
export function readStoredJson(bytes: Uint8Array | ArrayBuffer): unknown | null {
  const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const text = new TextDecoder().decode(data).replace(/^\uFEFF/, "").trim();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    // Older files may contain a trailing comma written by an interrupted save.
    try {
      return JSON.parse(text.replace(/,\s*([}\]])/g, "$1"));
    } catch {
      return null;
    }
  }
}

// Account sync: only accepts JSON objects because profiles, notes, and history rows are objects.
export function readStoredObject(bytes: Uint8Array | ArrayBuffer): Record<string, unknown> | null {
  const value = readStoredJson(bytes);
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}
