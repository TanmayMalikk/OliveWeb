// Safe-app users: shows the user private notes and note trash.
import { useEffect, useState } from "react";
import { Pin, Plus, RotateCcw, Search, Trash2 } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Empty, Field, Form, Modal } from "../../../components/SharedComponents";
import { purgeEntry, TRASH_RETENTION_MS, type Entry } from "../../../domain/DomainModels";
import { formatDateTime } from "../../shared/safe_app/ActivityHistoryPage";
import { accounts } from "../../../services/AccountService";
import {
  listBrowserDocuments,
  setBrowserDocumentEvidence,
  uploadBrowserDocument,
  type BrowserDocument,
} from "../../../services/MediaService";
import { IncidentLocationPicker } from "./IncidentLocationPicker";
export default function Notes() {
  const s = useSession();
  const [query, setQuery] = useState("");
  const [trash, setTrash] = useState(false);
  const [editing, setEditing] = useState<Entry | null>(null);
  const [revisions, setRevisions] = useState<Entry | null>(null);
  const [now, setNow] = useState(Date.now());
  const [documents, setDocuments] = useState<BrowserDocument[]>([]);
  const [attachmentUploadProgress, setAttachmentUploadProgress] = useState<number | null>(null);
  const [attachmentError, setAttachmentError] = useState("");
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    if (!accounts.identity) return;
    void listBrowserDocuments(accounts.identity).then(setDocuments).catch(() => setDocuments([]));
  }, []);
  const entries = s.history.private_entries.map((e) => purgeEntry(e, now));
  // Permanently removes trashed notes after their retention period ends.
  useEffect(() => {
    const expired = s.history.private_entries.find(
      (e) =>
        e.deletedAt &&
        !e.purgedAt &&
        Date.now() - e.deletedAt >= TRASH_RETENTION_MS,
    );
    if (expired && !s.busy)
      void s.perform(() => s.record("private_entries", purgeEntry(expired)));
  }, [now, s.history.private_entries, s.busy]);
  const shown = entries
    .filter(
      (e) =>
        !e.purgedAt &&
        Boolean(e.deletedAt) === trash &&
        `${e.title} ${e.body}`.toLowerCase().includes(query.toLowerCase()),
    )
    .sort(
      (a, b) =>
        Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt,
    );
  // Web user notes page: Adds the new item.
  function add() {
    const timestamp = Date.now();
    setEditing({
      id: crypto.randomUUID(),
      timestamp,
      updatedAt: timestamp,
      title: "",
      body: "",
      kind: "Note",
      pinned: false,
      edits: [],
    });
  }
  return (
    <>
      <div className="page-toolbar">
        <label className="search-field">
          <Search size={17} />
          <span className="sr-only">Search notes</span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Find a thought…"
          />
        </label>
        <div className="toolbar-actions">
          <Button variant="quiet" onClick={() => setTrash(!trash)}>
            <Trash2 size={17} />
            {trash ? "Back to notes" : "Trash"}
          </Button>
          <Button onClick={add}>
            <Plus size={17} /> New entry
          </Button>
        </div>
      </div>
      {trash && (
        <p className="help-note">
          The mobile project currently uses a 10-second test retention period.
          Entries expire after 10 seconds here too.
        </p>
      )}
      <div className="notes-grid">
        {shown.map((entry) => (
          <article className="note-card panel" key={entry.id}>
            <div className="note-meta">
              <span className="tag">
                {entry.kind === "Incident" ? "Incident" : "Note"}
              </span>
              <time>{new Date(entry.updatedAt).toLocaleDateString()}</time>
            </div>
            <button className="note-open" onClick={() => setEditing(entry)}>
              <h2>{entry.title || "Untitled thought"}</h2>
              <p>{entry.body}</p>
              {(entry.attachmentIds?.length ?? 0) > 0 && (
                <small>{entry.attachmentIds!.length} attached file{entry.attachmentIds!.length === 1 ? "" : "s"}</small>
              )}
            </button>
            <div className="note-actions">
              {trash ? (
                <>
                  <small>
                    Expires in{" "}
                    {Math.max(
                      0,
                      Math.ceil(
                        (TRASH_RETENTION_MS - (now - entry.deletedAt!)) / 1000,
                      ),
                    )}
                    s
                  </small>
                  <Button
                    variant="quiet"
                    onClick={() =>
                      void s.perform(async () => {
                        const { deletedAt: _, ...rest } = entry;
                        await s.saveEntry({ ...rest, updatedAt: Date.now() });
                      })
                    }
                  >
                    <RotateCcw size={16} /> Restore
                  </Button>
                </>
              ) : (
                <>
                  <button
                    className="icon-button"
                    aria-label={`${entry.pinned ? "Unpin" : "Pin"} ${entry.title || "entry"}`}
                    aria-pressed={entry.pinned}
                    onClick={() =>
                      void s.perform(() =>
                        s.record("private_entries", {
                          ...entry,
                          pinned: !entry.pinned,
                        }),
                      )
                    }
                  >
                    <Pin
                      size={17}
                      fill={entry.pinned ? "currentColor" : "none"}
                    />
                  </button>
                  <button
                    className="text-link"
                    onClick={() => setRevisions(entry)}
                  >
                    Edit history
                  </button>
                  <button
                    className="icon-button"
                    aria-label={`Move ${entry.title || "entry"} to trash`}
                    onClick={() =>
                      void s.perform(() =>
                        s.record("private_entries", {
                          ...entry,
                          deletedAt: Date.now(),
                          updatedAt: Date.now(),
                        }),
                      )
                    }
                  >
                    <Trash2 size={17} />
                  </button>
                </>
              )}
            </div>
          </article>
        ))}
      </div>
      {!shown.length && (
        <Empty
          title={
            trash
              ? "Nothing in the trash."
              : "A blank page can be a good place to start."
          }
          action={
            !trash ? (
              <Button onClick={add}>
                <Plus size={16} /> Write your first entry
              </Button>
            ) : undefined
          }
        >
          {trash
            ? "Expired entries are removed from view."
            : "Write a note or record an incident. You choose what belongs here."}
        </Empty>
      )}
      {editing && (
        <Modal
          title={editing.body ? "Your entry" : "A new thought"}
          wide
          onClose={() => setEditing(null)}
        >
          <Form
            onSubmit={async (data) => {
              await s.perform(async () => {
                const title = String(data.get("title")).trim();
                const body = String(data.get("body")).trim();
                const kind = data.get("kind") as Entry["kind"];
                const incidentDate = String(data.get("incidentDate") ?? "").trim();
                const incidentTime = String(data.get("incidentTime") ?? "").trim();
                const incidentLocation = String(data.get("incidentLocation") ?? "").trim();
                const latitudeText = String(data.get("incidentLatitude") ?? "").trim();
                const longitudeText = String(data.get("incidentLongitude") ?? "").trim();
                const incidentLatitude = latitudeText ? Number(latitudeText) : Number.NaN;
                const incidentLongitude = longitudeText ? Number(longitudeText) : Number.NaN;
                const attachmentIds = data.getAll("attachments").map(String);
                if (!body) throw new Error("Write something before saving.");
                if (kind === "Incident" && (!title || !incidentDate || !incidentLocation))
                  throw new Error("Add a title, incident date, and incident location.");
                const changes = [
                  ["Title", editing.title, title],
                  ["Body", editing.body, body],
                  ["Kind", editing.kind ?? "", kind ?? ""],
                  ["Incident date", editing.incidentDate ?? "", incidentDate],
                  ["Approximate time", editing.incidentTime ?? "", incidentTime],
                  ["Incident location", editing.incidentLocation ?? "", incidentLocation],
                  ["Attachments", (editing.attachmentIds ?? []).join(","), attachmentIds.join(",")],
                ]
                  .filter(([, before, after]) => before !== after)
                  .map(([field, before, after]) => ({
                    field: field!,
                    before: before!,
                    after: after!,
                  }));
                await s.saveEntry({
                  ...editing,
                  title,
                  body,
                  kind,
                  incidentDate: kind === "Incident" ? incidentDate : "",
                  incidentTime: kind === "Incident" ? incidentTime : "",
                  incidentLocation: kind === "Incident" ? incidentLocation : "",
                  incidentLatitude:
                    kind === "Incident" && Number.isFinite(incidentLatitude)
                      ? incidentLatitude
                      : undefined,
                  incidentLongitude:
                    kind === "Incident" && Number.isFinite(incidentLongitude)
                      ? incidentLongitude
                      : undefined,
                  attachmentIds,
                  updatedAt: Date.now(),
                  edits:
                    editing.body && changes.length
                      ? [
                          {
                            id: crypto.randomUUID(),
                            timestamp: Date.now(),
                            changes,
                          },
                          ...editing.edits,
                        ]
                      : editing.edits,
                });
                if (kind === "Incident") {
                  await Promise.all(
                    documents.filter((document) => attachmentIds.includes(document.id) && !document.evidence)
                      .map((document) => setBrowserDocumentEvidence(document.id, true)),
                  );
                }
                setEditing(null);
              });
            }}
          >
            <div className="entry-type-tabs" role="tablist" aria-label="Entry type">
              {(["Note", "Incident"] as const).map((kind) => (
                <button
                  key={kind}
                  type="button"
                  role="tab"
                  aria-selected={editing.kind === kind}
                  className={editing.kind === kind ? "active" : ""}
                  onClick={() => setEditing({ ...editing, kind })}
                >
                  {kind}
                </button>
              ))}
              <input type="hidden" name="kind" value={editing.kind} />
            </div>
            <Field
              label={editing.kind === "Incident" ? "Title" : "Title (optional)"}
              name="title"
              defaultValue={editing.title}
              maxLength={160}
              required={editing.kind === "Incident"}
            />
            {editing.kind === "Incident" && (
              <>
                <Field label="Date of incident" name="incidentDate" type="date" defaultValue={editing.incidentDate ?? ""} required />
                <Field label="Approx. time of incident" name="incidentTime" type="time" defaultValue={editing.incidentTime ?? ""} />
                <IncidentLocationPicker
                  label={editing.incidentLocation ?? ""}
                  latitude={editing.incidentLatitude}
                  longitude={editing.incidentLongitude}
                  onChange={(location) => setEditing((current) => current && ({
                    ...current,
                    incidentLocation: location.label,
                    incidentLatitude: Number.isFinite(location.latitude) ? location.latitude : undefined,
                    incidentLongitude: Number.isFinite(location.longitude) ? location.longitude : undefined,
                  }))}
                />
                <input type="hidden" name="incidentLocation" value={editing.incidentLocation ?? ""} />
                <input type="hidden" name="incidentLatitude" value={editing.incidentLatitude ?? ""} />
                <input type="hidden" name="incidentLongitude" value={editing.incidentLongitude ?? ""} />
              </>
            )}
            <label className="field">
              <span>Your words</span>
              <textarea
                name="body"
                defaultValue={editing.body}
                rows={9}
                maxLength={20000}
                required
                autoFocus
              />
            </label>
            <fieldset className="field attachment-picker">
              <legend>Attachments</legend>
              <p>Choose saved photos, videos, or documents. Incident attachments become evidence.</p>
              <label className="button outline">
                {attachmentUploadProgress === null
                  ? "Upload a new attachment"
                  : `Uploading ${Math.round(attachmentUploadProgress * 100)}%`}
                <input
                  className="sr-only"
                  type="file"
                  accept="image/*,video/*,application/pdf,text/*"
                  disabled={attachmentUploadProgress !== null || !accounts.identity}
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    const identity = accounts.identity;
                    if (!file || !identity) return;
                    const previous = new Set(documents.map((document) => document.id));
                    setAttachmentError("");
                    setAttachmentUploadProgress(0);
                    void uploadBrowserDocument(file, identity, setAttachmentUploadProgress)
                      .then(() => listBrowserDocuments(identity))
                      .then((updated) => {
                        setDocuments(updated);
                        const added = updated.find((document) => !previous.has(document.id));
                        if (added) setEditing((current) => current && ({
                          ...current,
                          attachmentIds: [...new Set([...(current.attachmentIds ?? []), added.id])],
                        }));
                      })
                      .catch((failure) => setAttachmentError(
                        failure instanceof Error ? failure.message : "Unable to upload this attachment.",
                      ))
                      .finally(() => {
                        setAttachmentUploadProgress(null);
                        event.target.value = "";
                      });
                  }}
                />
              </label>
              {attachmentError && <small className="error" role="alert">{attachmentError}</small>}
              {documents.map((document) => (
                <label key={document.id}>
                  <input
                    type="checkbox"
                    name="attachments"
                    value={document.id}
                    defaultChecked={(editing.attachmentIds ?? []).includes(document.id)}
                  />
                  {document.name}{document.evidence ? " · Evidence" : ""}
                </label>
              ))}
              {!documents.length && <small>Upload files in Documents, then attach them here.</small>}
            </fieldset>
            <div className="modal-actions">
              <Button type="submit" disabled={s.busy}>
                Save entry
              </Button>
              <Button variant="quiet" onClick={s.quickExit}>
                Quick Exit
              </Button>
            </div>
          </Form>
        </Modal>
      )}
      {revisions && (
        <Modal title="Entry edit history" onClose={() => setRevisions(null)}>
          {revisions.edits.length ? (
            revisions.edits.map((edit) => (
              <div className="revision" key={edit.id}>
                <time>{formatDateTime(edit.timestamp)}</time>
                {edit.changes.map((change) => (
                  <div key={change.field}>
                    <strong>{change.field}</strong>
                    <del>{change.before || "Empty"}</del>
                    <p>{change.after || "Empty"}</p>
                  </div>
                ))}
              </div>
            ))
          ) : (
            <p>No edits have been recorded.</p>
          )}
        </Modal>
      )}
    </>
  );
}


