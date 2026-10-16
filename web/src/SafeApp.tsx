import { lazy, Suspense, useEffect, useState } from "react";
import {
  House,
  NotebookPen,
  MessageCircle,
  Users,
  UserRound,
  Settings,
  History as HistoryIcon,
  LogOut,
  ArrowRight,
  Plus,
  Pin,
  Trash2,
  RotateCcw,
  Search,
  Copy,
  Check,
  ShieldCheck,
  RefreshCw,
  Leaf,
} from "lucide-react";
import { useSession } from "./session";
import {
  Branch,
  Button,
  Empty,
  Field,
  Form,
  Modal,
  Forward,
} from "./components";
import {
  newEvent,
  purgeEntry,
  TRASH_RETENTION_MS,
  validPassword,
  validPin,
  type Entry,
  type Page,
  type Profile,
} from "./domain";
import { accounts, triggerHash } from "./services/accounts";
import { issueEntryLink, savedEntryLink } from "./services/entry-links";
const Messages = lazy(() => import("./Messages"));
const nav: { page: Page; icon: typeof House }[] = [
  { page: "Home", icon: House },
  { page: "Notes", icon: NotebookPen },
  { page: "Messages", icon: MessageCircle },
  { page: "Contacts", icon: Users },
  { page: "Profile", icon: UserRound },
  { page: "Settings", icon: Settings },
  { page: "Activity", icon: HistoryIcon },
];
const date = (time: number) =>
  new Date(time).toLocaleString([], {
    dateStyle: "medium",
    timeStyle: "short",
  });
export default function SafeApp() {
  const s = useSession();
  const p = s.profile!;
  return (
    <div className="safe-shell private-shell">
      <aside className="safe-sidebar">
        <a href="#main" className="safe-brand">
          <Leaf strokeWidth={1.2} />
          <span>
            A little space.<small>YOUR OLIVE BRANCH</small>
          </span>
        </a>
        <nav aria-label="Personal space navigation">
          {nav.map(({ page, icon: Icon }) => (
            <button
              key={page}
              aria-current={s.page === page ? "page" : undefined}
              onClick={() => {
                s.clearMessage();
                s.setPage(page);
              }}
            >
              <Icon size={20} />
              <span>{page}</span>
              {s.page === page && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="safe-sidebar-bottom">
          <span className="avatar">{p.username[0].toUpperCase()}</span>
          <div>
            <strong>@{p.username}</strong>
            <small>
              {s.cloudMode ? "Shared account" : "Browser-only account"}
            </small>
          </div>
        </div>
      </aside>
      <div className="safe-main">
        <header className="safe-header">
          <span className="eyebrow">YOUR SPACE, AT YOUR PACE</span>
          <Button variant="exit" onClick={s.quickExit}>
            Quick Exit <LogOut size={17} />
          </Button>
        </header>
        <main id="main" tabIndex={-1}>
          <div className="safe-page-title">
            <div>
              <span className="eyebrow">A PLACE THAT’S YOURS</span>
              <h1>
                {s.page === "Home"
                  ? `Welcome, ${p.fullName.split(" ")[0] || p.username}.`
                  : s.page === "Notes"
                    ? "A place for your thoughts."
                    : s.page === "Contacts"
                      ? "People in your corner."
                      : s.page === "Messages"
                        ? "Stay connected."
                        : s.page === "Settings"
                          ? "Your space. Your settings."
                          : s.page === "Activity"
                            ? "Your account, in perspective."
                            : "Your story, your words."}
              </h1>
            </div>
            <small className="today">
              {new Date().toLocaleDateString([], {
                weekday: "long",
                month: "long",
                day: "numeric",
              })}
            </small>
          </div>
          {s.message && (
            <div className="status-banner" role="status">
              {s.message}
              <button className="text-link" onClick={s.clearMessage}>
                Dismiss
              </button>
            </div>
          )}
          {s.page === "Home" ? (
            <Home />
          ) : s.page === "Notes" ? (
            <Notes />
          ) : s.page === "Contacts" ? (
            <Contacts />
          ) : s.page === "Profile" ? (
            <ProfilePage />
          ) : s.page === "Settings" ? (
            <SettingsPage />
          ) : s.page === "Activity" ? (
            <Activity />
          ) : (
            <Suspense fallback={<p role="status">Opening conversations…</p>}>
              <Messages />
            </Suspense>
          )}
        </main>
        <footer className="safe-footer">
          <span>A small step is still a step.</span>
          <button
            className="text-link"
            onClick={() => void s.perform(s.signOut)}
          >
            Sign out
          </button>
        </footer>
      </div>
      {s.idleSeconds !== null && (
        <Modal
          title="Would you like a little more time?"
          onClose={s.noteInteraction}
        >
          <p>
            Your space locks after inactivity. Returning to your cover in{" "}
            {s.idleSeconds} seconds.
          </p>
          <Button onClick={s.noteInteraction}>Stay here</Button>
          <Button variant="quiet" onClick={s.quickExit}>
            Quick Exit
          </Button>
        </Modal>
      )}
    </div>
  );
}
function Home() {
  const s = useSession();
  return (
    <>
      <section className="safe-welcome">
        <div>
          <span className="eyebrow">THERE’S NO PERFECT PLACE TO START</span>
          <h2>
            One thought.
            <br />
            <em>One small step.</em>
          </h2>
          <p>
            This space is yours. Write something down, reach out to someone you
            trust, or simply take a moment.
          </p>
          <Button onClick={() => s.setPage("Notes")}>
            <Forward>Put a thought into words</Forward>
          </Button>
        </div>
        <Branch />
      </section>
      <div className="safe-card-grid">
        {[
          {
            title: "Your thoughts, kept together.",
            copy: "Notes and incident records, in your own words.",
            page: "Notes" as Page,
            Icon: NotebookPen,
          },
          {
            title: "A familiar voice.",
            copy: "Connect with the people you choose to trust.",
            page: "Messages" as Page,
            Icon: MessageCircle,
          },
          {
            title: "Your people, close by.",
            copy: "Keep trusted contact details in one place.",
            page: "Contacts" as Page,
            Icon: Users,
          },
        ].map(({ title, copy, page, Icon }) => (
          <button
            className="safe-shortcut panel"
            key={page}
            onClick={() => s.setPage(page)}
          >
            <Icon size={24} strokeWidth={1.3} />
            <h3>{title}</h3>
            <p>{copy}</p>
            <ArrowRight size={20} />
          </button>
        ))}
      </div>
      <section className="privacy-note">
        <ShieldCheck size={22} />
        <div>
          <h3>A quick way back.</h3>
          <p>
            Quick Exit or Escape returns to your cover. Switching tabs locks
            this space. A website cannot prevent screenshots or erase browser
            history.
          </p>
        </div>
      </section>
    </>
  );
}
function Notes() {
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
      <div className="workspace-toolbar">
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
                <time>{date(edit.timestamp)}</time>
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
function Contacts() {
  const s = useSession();
  const p = s.profile!;
  const [editing, setEditing] = useState<{
    id: string;
    name: string;
    details: string;
  } | null>(null);
  return (
    <>
      <div className="workspace-toolbar">
        <p>A name. A number. Someone you trust.</p>
        <Button
          disabled={p.contacts.length >= 25}
          onClick={() =>
            setEditing({ id: crypto.randomUUID(), name: "", details: "" })
          }
        >
          <Plus size={17} /> Add a contact
        </Button>
      </div>
      <div className="safe-card-grid">
        {p.contacts.map((contact) => (
          <article className="contact-card panel" key={contact.id}>
            <span className="avatar">{contact.name[0].toUpperCase()}</span>
            <h2>{contact.name}</h2>
            <p className="preserve-lines">{contact.details}</p>
            <div className="toolbar-actions">
              <Button variant="quiet" onClick={() => setEditing(contact)}>
                Edit
              </Button>
              <Button
                variant="quiet"
                onClick={() =>
                  void s.perform(() =>
                    s.saveProfile(
                      {
                        ...p,
                        contacts: p.contacts.filter((c) => c.id !== contact.id),
                      },
                      "Removed a trusted contact",
                    ),
                  )
                }
              >
                Remove
              </Button>
            </div>
          </article>
        ))}
      </div>
      {!p.contacts.length && (
        <Empty title="You don’t have to hold every detail in your head.">
          Add up to 25 trusted contacts. Their details stay inside this space.
        </Empty>
      )}
      {editing && (
        <Modal title="A person in your corner" onClose={() => setEditing(null)}>
          <Form
            onSubmit={async (data) => {
              await s.perform(async () => {
                const contact = {
                  ...editing,
                  name: String(data.get("name")).trim(),
                  details: String(data.get("details")).trim(),
                };
                if (!contact.name || !contact.details)
                  throw new Error("Add a name and contact details.");
                await s.saveProfile(
                  {
                    ...p,
                    contacts: [
                      ...p.contacts.filter((c) => c.id !== contact.id),
                      contact,
                    ],
                  },
                  "Saved a trusted contact",
                );
                setEditing(null);
              });
            }}
          >
            <Field
              label="Name"
              name="name"
              defaultValue={editing.name}
              required
              maxLength={120}
            />
            <Field
              label="Phone, email, or other details"
              name="details"
              defaultValue={editing.details}
              required
              maxLength={200}
            />
            <Button type="submit" disabled={s.busy}>
              Save contact
            </Button>
          </Form>
        </Modal>
      )}
    </>
  );
}
function ProfilePage() {
  const s = useSession();
  const p = s.profile!;
  return (
    <Form
      className="panel profile-form"
      onSubmit={async (data) => {
        await s.perform(async () => {
          const next = { ...p };
          for (const key of [
            "fullName",
            "dob",
            "affectedNames",
            "abuserName",
            "situation",
            "language",
            "email",
            "phone",
          ] as const)
            next[key] = String(data.get(key) ?? p[key]);
          for (const key of [
            "line1",
            "line2",
            "city",
            "province",
            "postalCode",
            "country",
          ] as const)
            next.address = {
              ...next.address,
              [key]: String(data.get(key) ?? p.address[key]),
            };
          next.region = next.address.city;
          if (
            !next.fullName.trim() ||
            !next.address.city.trim() ||
            new Date(next.dob) > new Date()
          )
            throw new Error("Add a name, valid birth date, and city.");
          await s.saveProfile(next);
        });
      }}
    >
      <h2>The details that matter to you.</h2>
      <div className="form-grid">
        <Field
          label="Full name"
          name="fullName"
          defaultValue={p.fullName}
          required
          maxLength={120}
        />
        <Field
          label="Date of birth"
          type="date"
          name="dob"
          defaultValue={p.dob}
          required
          max={new Date().toISOString().slice(0, 10)}
        />
        <Field
          label="People affected"
          name="affectedNames"
          defaultValue={p.affectedNames}
          maxLength={500}
        />
        <Field
          label="Person causing harm"
          name="abuserName"
          defaultValue={p.abuserName}
          maxLength={120}
        />
        <Field
          label="Email (optional)"
          name="email"
          type="email"
          defaultValue={p.email}
          maxLength={254}
        />
        <Field
          label="Phone (optional)"
          name="phone"
          defaultValue={p.phone}
          maxLength={40}
        />
      </div>
      <label className="field">
        <span>Your situation (optional)</span>
        <textarea
          name="situation"
          defaultValue={p.situation}
          maxLength={2000}
        />
      </label>
      <h3>Your location</h3>
      <div className="form-grid">
        {(
          [
            ["line1", "Street"],
            ["line2", "Apartment / unit"],
            ["city", "City"],
            ["province", "Province / state"],
            ["postalCode", "Postal code"],
            ["country", "Country"],
          ] as const
        ).map(([key, label]) => (
          <Field
            key={key}
            label={label}
            name={key}
            defaultValue={p.address[key]}
            required={key === "city"}
            maxLength={160}
          />
        ))}
      </div>
      <Button type="submit" disabled={s.busy}>
        Save profile <Check size={17} />
      </Button>
    </Form>
  );
}
function SettingsPage() {
  const s = useSession();
  const p = s.profile!;
  const [link, setLink] = useState<string | null>(null);
  const [confirmRotation, setConfirmRotation] = useState(false);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let live = true;
    void savedEntryLink(accounts.identity!.uid)
      .then((value) => {
        if (live) setLink(value);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);
  return (
    <div className="settings-grid">
      <section className="panel">
        <h2>Your everyday.</h2>
        <p>The cover you return to when you leave this space.</p>
        <Form
          onSubmit={async (data) => {
            await s.perform(() =>
              s.saveProfile(
                {
                  ...p,
                  coverType: String(data.get("cover")) as Profile["coverType"],
                  language: String(data.get("language")),
                },
                "Updated cover and language",
              ),
            );
          }}
        >
          <label className="field">
            <span>Cover app</span>
            <select name="cover" defaultValue={p.coverType}>
              <option value="FOOD">Olive</option>
              <option value="NEWS">Daily Digest</option>
              <option value="CALCULATOR">Better Calculator</option>
              <option value="GAME">Little Grove</option>
            </select>
          </label>
          <label className="field">
            <span>Profile language</span>
            <select name="language" defaultValue={p.language}>
              {[
                "English",
                "French",
                "Spanish",
                "Arabic",
                "Chinese",
                "Tagalog",
                "Cree",
              ].map((l) => (
                <option key={l}>{l}</option>
              ))}
            </select>
            <small>
              Your language preference syncs with mobile. The web interface
              currently uses English.
            </small>
          </label>
          <Button type="submit" disabled={s.busy}>
            Save preferences
          </Button>
        </Form>
      </section>
      <section className="panel">
        <h2>Your personal link.</h2>
        <p>
          Your link opens an entry gate. A password or enrolled-browser PIN is
          still required.
        </p>
        {link && (
          <>
            <Field label="Personal entry link" value={link} readOnly />
            <Button
              variant="outline"
              onClick={() =>
                void navigator.clipboard
                  .writeText(link)
                  .then(() => setCopied(true))
                  .catch(() => {})
              }
            >
              <Copy size={16} />
              {copied ? "Copied" : "Copy link"}
            </Button>
          </>
        )}
        <Button variant="quiet" onClick={() => setConfirmRotation(true)}>
          <RefreshCw size={16} /> Create a replacement link
        </Button>
      </section>
      <section className="panel">
        <h2>Account password.</h2>
        <Form
          onSubmit={async (data) => {
            await s.perform(async () => {
              const next = String(data.get("next"));
              if (!validPassword(next) || next !== data.get("confirmation"))
                throw new Error(
                  "Use matching 8–24 character passwords with a letter, number, and special character.",
                );
              await accounts.changePassword(String(data.get("current")), next);
              await s.record("settings", newEvent("Changed account password"));
              s.clearMessage();
            });
          }}
        >
          <Field
            label="Current password"
            name="current"
            type="password"
            autoComplete="current-password"
            required
          />
          <Field
            label="New password"
            name="next"
            type="password"
            autoComplete="new-password"
            required
            maxLength={24}
          />
          <Field
            label="Confirm new password"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
            maxLength={24}
          />
          <Button type="submit" disabled={s.busy}>
            Change password
          </Button>
        </Form>
      </section>
      {p.role === "USER" && (
        <>
          <section className="panel">
            <h2>Browser PIN.</h2>
            <Form
              onSubmit={async (data) => {
                await s.perform(async () => {
                  const next = String(data.get("next"));
                  if (!validPin(next) || next !== data.get("confirmation"))
                    throw new Error("Enter matching PINs with 4–6 digits.");
                  await accounts.changePin(
                    String(data.get("current")),
                    next,
                    p,
                  );
                  await s.record("settings", newEvent("Changed browser PIN"));
                });
              }}
            >
              <Field
                label="Current PIN"
                name="current"
                type="password"
                inputMode="numeric"
                required
                maxLength={6}
              />
              <Field
                label="New PIN"
                name="next"
                type="password"
                inputMode="numeric"
                pattern="[0-9]{4,6}"
                required
                maxLength={6}
              />
              <Field
                label="Confirm new PIN"
                name="confirmation"
                type="password"
                inputMode="numeric"
                pattern="[0-9]{4,6}"
                required
                maxLength={6}
              />
              <Button type="submit" disabled={s.busy}>
                Change PIN
              </Button>
            </Form>
          </section>
          <section className="panel">
            <h2>Cover trigger.</h2>
            <p>
              Your web entry uses your personal link. This trigger preference
              also syncs with your Android cover.
            </p>
            <Form
              onSubmit={async (data) => {
                await s.perform(async () => {
                  const value = String(data.get("trigger"));
                  if (value.trim().length < 3 || value.trim().length > 64)
                    throw new Error("Choose 3–64 characters.");
                  await s.saveProfile(
                    { ...p, triggerHash: await triggerHash(value) },
                    "Updated private entry trigger",
                  );
                });
              }}
            >
              <Field
                label="New trigger phrase"
                name="trigger"
                type="password"
                minLength={3}
                maxLength={64}
                required
                autoComplete="off"
              />
              <Button type="submit" disabled={s.busy}>
                Save trigger
              </Button>
            </Form>
          </section>
        </>
      )}
      <section className="panel">
        <h2>Your account connection.</h2>
        <p>
          {s.cloudMode
            ? "Profiles, notes, contacts, and activity use the same Firebase paths as Android."
            : "This account is encrypted in this browser. It cannot read the Android app’s local vault."}
        </p>
        <Button
          variant="outline"
          disabled={s.busy}
          onClick={() => void s.perform(s.refresh)}
        >
          <RefreshCw size={16} /> Refresh account
        </Button>
        <p className="help-note">
          Quick Exit does not clear browser history or prevent screen recording.
          Keep access to your device and browser secure.
        </p>
      </section>
      {confirmRotation && (
        <Modal
          title="Replace your personal link?"
          onClose={() => setConfirmRotation(false)}
        >
          <p>
            Your previous link will stop working. Save the replacement before
            you leave.
          </p>
          <Button
            disabled={s.busy}
            onClick={() =>
              void s.perform(async () => {
                setLink(await issueEntryLink(accounts.identity!.uid));
                setConfirmRotation(false);
              })
            }
          >
            Create replacement
          </Button>
          <Button variant="quiet" onClick={() => setConfirmRotation(false)}>
            Keep current link
          </Button>
        </Modal>
      )}
    </div>
  );
}
function Activity() {
  const s = useSession();
  const [category, setCategory] = useState<
    "settings" | "triggers" | "checkouts"
  >("settings");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const rows = s.history[category]
    .filter(
      (e) =>
        (!from || e.timestamp >= new Date(from).getTime()) &&
        (!to || e.timestamp < new Date(to).getTime() + 86400000),
    )
    .sort((a, b) => b.timestamp - a.timestamp);
  return (
    <>
      <div className="workspace-toolbar">
        <div className="filter-row">
          {(["settings", "triggers", "checkouts"] as const).map((c) => (
            <button
              key={c}
              aria-pressed={category === c}
              onClick={() => setCategory(c)}
            >
              {c[0].toUpperCase() + c.slice(1)}
            </button>
          ))}
        </div>
        <div className="form-grid">
          <Field
            label="From date"
            type="date"
            value={from}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setFrom(e.target.value)
            }
          />
          <Field
            label="To date"
            type="date"
            value={to}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setTo(e.target.value)
            }
          />
        </div>
      </div>
      <div className="activity-list panel">
        {rows.map((row) => (
          <article key={row.id}>
            <span className="activity-dot" />
            <div>
              <h3>
                {row.action || row.source || row.error || "Account activity"}
              </h3>
              <p>
                {row.area ??
                  row.lastResult ??
                  row.foods?.map((f) => `${f.quantity} × ${f.food}`).join(", ")}
              </p>
              {row.changes?.map((change) => (
                <p key={change.field}>
                  {change.field}: {change.before || "Empty"} →{" "}
                  {change.after || "Empty"}
                </p>
              ))}
              {row.totalCents !== undefined && (
                <strong>${(row.totalCents / 100).toFixed(2)}</strong>
              )}
            </div>
            <time>{date(row.timestamp)}</time>
          </article>
        ))}
        {!rows.length && (
          <Empty title="A little quiet here.">
            Activity will appear as you use your account.
          </Empty>
        )}
      </div>
    </>
  );
}
