// Safe-app users: shows the user private notes and note trash.
import { useEffect, useState } from "react";
import { Pin, Plus, RotateCcw, Search, Trash2 } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Empty, Field, Form, Modal } from "../../../components/SharedComponents";
import { purgeEntry, TRASH_RETENTION_MS, type Entry } from "../../../domain/DomainModels";
import { formatDateTime } from "../../shared/safe_app/ActivityHistoryPage";
export default function Notes() {
  const s = useSession();
  const [query, setQuery] = useState("");
  const [trash, setTrash] = useState(false);
  const [editing, setEditing] = useState<Entry | null>(null);
  const [revisions, setRevisions] = useState<Entry | null>(null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const entries = s.history.private_entries.map((e) => purgeEntry(e, now));
  // Update entries when information used by this screen changes.
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
                if (!body) throw new Error("Write something before saving.");
                const changes = [
                  ["Title", editing.title, title],
                  ["Body", editing.body, body],
                  ["Kind", editing.kind ?? "", kind ?? ""],
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
                setEditing(null);
              });
            }}
          >
            <label className="field">
              <span>Entry type</span>
              <select name="kind" defaultValue={editing.kind ?? "Note"}>
                <option>Note</option>
                <option>Incident</option>
              </select>
            </label>
            <Field
              label="Title (optional)"
              name="title"
              defaultValue={editing.title}
              maxLength={160}
            />
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


