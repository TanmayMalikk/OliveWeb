// Safe-app users: shows the user safe contacts and friends.
import { useState } from "react";
import { Plus } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Empty, Field, Form, Modal } from "../../../components/SharedComponents";
export default function Contacts() {
  const s = useSession();
  const p = s.profile!;
  const [editing, setEditing] = useState<{
    id: string;
    name: string;
    details: string;
  } | null>(null);
  return (
    <>
      <div className="page-toolbar">
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

