// Web and Android messaging: turns shared-item message codes into readable attachment details.
export type SharedMessage =
  | { kind: "media"; id: string; title: string }
  | { kind: "incident" | "note"; id: string; title: string }
  | { kind: "safety-plan"; title: string }
  | { kind: "location"; title: string };

export function sharedMessage(body: string): SharedMessage | null {
  const [type, id = "", title = ""] = body.split(":", 3);
  if (type === "SHARED_MEDIA" && id)
    return { kind: "media", id, title: title || "Shared file" };
  if (type === "SHARED_INCIDENT" && id)
    return { kind: "incident", id, title: title || "Incident report" };
  if (type === "SHARED_NOTE" && id)
    return { kind: "note", id, title: title || "Private note" };
  if (type === "SHARED_SAFETY_PLAN")
    return { kind: "safety-plan", title: title || "Shared safety plan" };
  if (type === "SHARED_LOCATION")
    return { kind: "location", title: "Shared live location" };
  return null;
}
