// Safe-app users: shows the user safe contacts and friends.
import { useEffect, useState } from "react";
import { Plus } from "lucide-react";
import type { Contact } from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { Button, Empty, Field, Form, Modal, shakeNamedFields } from "../../../components/SharedComponents";
import { useWalkthroughAction } from "../../shared/safe_app/WalkthroughCoachmark";
export default function Contacts() {
  const s = useSession();
  const p = s.profile!;
  const [editing, setEditing] = useState<Contact | null>(null);
  const [wtOpened, setWtOpened] = useState(false);
  const wt = useWalkthroughAction();
  useEffect(() => {
    if (wt.action === "contacts_open_add_modal") {
      setEditing((cur) => cur ?? { id: crypto.randomUUID(), name: "", details: "" });
      setWtOpened(true);
    } else if (wtOpened) {
      setEditing(null);
      setWtOpened(false);
    }
  }, [wt.stepId, wt.action]);
  return (
    <>
      <div className="page-toolbar">
        <p>A name. A number. Someone you trust. ({p.contacts.length} of 100)</p>
        <Button
          disabled={p.contacts.length >= 100}
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
            <span className="avatar">{(contact.name.trim()[0] || "?").toUpperCase()}</span>
            <h2>{contact.name}</h2>
            {contact.relationship && <p>{contact.relationship}</p>}
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
          Add up to 100 trusted contacts. Their details stay inside this space.
        </Empty>
      )}
      {editing && (
        <Modal
          title={editing.name ? "Edit contact" : "A person in your corner"}
          nonModal={wtOpened}
          onClose={() => { setEditing(null); setWtOpened(false); }}
        >
          <Form
            onSubmit={async (data, form) => {
              await s.perform(async () => {
                const contact = {
                  ...editing,
                  name: String(data.get("name")).trim(),
                  details: String(data.get("details")).trim(),
                  relationship: String(data.get("relationship") ?? "").trim(),
                };
                if (!contact.name || !contact.details) {
                  shakeNamedFields(
                    form,
                    [!contact.name ? "name" : "", !contact.details ? "details" : ""].filter(Boolean),
                  );
                  throw new Error("Add a name and contact details.");
                }
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
              label="Relationship (optional)"
              name="relationship"
              defaultValue={editing.relationship ?? ""}
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

