import { useEffect, useState } from "react";
import { Plus, LogOut, Users, RefreshCw } from "lucide-react";
import { Button, Field, Form, Modal, Empty, Logo } from "./components";
import { useSession } from "./session";
import { cloudMode, firebase } from "./services/accounts";
import type { Role } from "./domain";
interface Account {
  uid: string;
  username: string;
  role: Role;
  fullName: string;
  email: string;
  phone: string;
  profileComplete: boolean;
  protected: boolean;
}
export default function Portal() {
  const s = useSession();
  const [rows, setRows] = useState<Account[]>([]);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<Account | null>(null);
  async function call(name: string, input: unknown = {}) {
    if (!cloudMode)
      throw new Error(
        "Organization management requires a Firebase-connected account.",
      );
    const { functions } = await firebase();
    const { httpsCallable } = await import("firebase/functions");
    return (await httpsCallable(functions, name)(input)).data;
  }
  async function refresh() {
    const result = (await call("adminListAccounts")) as { accounts: Account[] };
    setRows(result.accounts);
  }
  useEffect(() => {
    if (s.profile?.role === "ADMIN") void s.perform(refresh);
  }, []);
  return (
    <div className="page-width private-shell portal">
      <header className="site-header">
        <Logo />
        <span className="eyebrow">ORGANIZATION WORKSPACE</span>
        <Button variant="exit" onClick={s.quickExit}>
          Quick Exit <LogOut size={17} />
        </Button>
      </header>
      <main id="main">
        <div className="safe-page-title">
          <div>
            <span className="eyebrow">
              {s.profile?.role === "ADMIN" ? "ADMINISTRATOR" : "STAFF"}
            </span>
            <h1>Good work starts with people.</h1>
          </div>
        </div>
        {s.message && (
          <p role="alert" className="form-error">
            {s.message}
          </p>
        )}
        {s.profile?.role === "ADMIN" ? (
          <>
            <div className="workspace-toolbar">
              <Field
                label="Search accounts"
                value={search}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setSearch(e.target.value)
                }
              />
              <div className="toolbar-actions">
                <Button
                  variant="outline"
                  onClick={() => void s.perform(refresh)}
                  disabled={s.busy}
                >
                  <RefreshCw size={16} /> Refresh
                </Button>
                <Button onClick={() => setCreating(true)}>
                  <Plus size={17} /> Create account
                </Button>
              </div>
            </div>
            <div className="table-scroll panel">
              <table>
                <thead>
                  <tr>
                    <th>Username</th>
                    <th>Role</th>
                    <th>Name</th>
                    <th>Profile</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {rows
                    .filter((row) =>
                      `${row.username} ${row.fullName} ${row.role}`
                        .toLowerCase()
                        .includes(search.toLowerCase()),
                    )
                    .map((row) => (
                      <tr key={row.uid}>
                        <td>@{row.username}</td>
                        <td>{row.role}</td>
                        <td>{row.fullName || "—"}</td>
                        <td>
                          {row.profileComplete ? "Complete" : "Needs setup"}
                        </td>
                        <td>
                          <Button
                            variant="quiet"
                            disabled={row.protected || s.busy}
                            onClick={() => setRemoving(row)}
                          >
                            Delete
                          </Button>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <Empty title="Your organization profile.">
            <p>{s.profile?.fullName}</p>
            <p>{s.profile?.email}</p>
            <p>
              Only administrators can create, list, or delete managed accounts.
            </p>
          </Empty>
        )}
        <Button variant="quiet" onClick={() => void s.perform(s.signOut)}>
          Sign out
        </Button>
      </main>
      {creating && (
        <Modal
          title="Create a managed account"
          onClose={() => setCreating(false)}
        >
          <Form
            onSubmit={async (data) => {
              await s.perform(async () => {
                await call(
                  "adminCreateAccount",
                  Object.fromEntries(data.entries()),
                );
                setCreating(false);
                await refresh();
              });
            }}
          >
            <Field
              label="Username"
              name="username"
              required
              minLength={8}
              maxLength={24}
            />
            <Field
              label="Temporary password"
              name="password"
              type="password"
              required
              minLength={8}
              maxLength={24}
            />
            <label className="field">
              <span>Role</span>
              <select name="role">
                <option value="USER">User</option>
                <option value="SAFE_CONTACT">Safe contact</option>
                <option value="STAFF">Staff</option>
                <option value="ADMIN">Administrator</option>
              </select>
            </label>
            <Field label="Full name" name="fullName" maxLength={120} />
            <Field label="Date of birth" name="dob" type="date" />
            <Field
              label="Email (for staff and administrators)"
              name="email"
              type="email"
              maxLength={254}
            />
            <Field label="City" name="region" maxLength={160} />
            <Button type="submit" disabled={s.busy}>
              Create account
            </Button>
          </Form>
        </Modal>
      )}
      {removing && (
        <Modal title="Delete this account?" onClose={() => setRemoving(null)}>
          <p>
            This permanently deletes @{removing.username} and their cloud
            records. This cannot be undone.
          </p>
          <Button
            disabled={s.busy}
            onClick={() =>
              void s.perform(async () => {
                await call("adminDeleteAccount", { uid: removing.uid });
                setRemoving(null);
                await refresh();
              })
            }
          >
            Delete @{removing.username}
          </Button>
          <Button variant="quiet" onClick={() => setRemoving(null)}>
            Cancel
          </Button>
        </Modal>
      )}
    </div>
  );
}
