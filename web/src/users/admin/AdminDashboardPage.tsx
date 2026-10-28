// Admins: shows account totals and controls to admins.
import { useEffect, useState } from "react";
import { LogOut, Plus, RefreshCw } from "lucide-react";
import { Button, Field, Form, Logo, Modal } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import {
  createAccount,
  deleteAccount,
  listAccounts,
  type ManagedAccount,
} from "./AdminAccountApi";

export default function AdminPortal() {
  const session = useSession();
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<ManagedAccount | null>(null);

  async function refresh() {
    setAccounts(await listAccounts());
  }

  useEffect(() => {
    void session.perform(refresh);
  }, []);

  return (
    <div className="page-width private-shell portal">
      <header className="site-header">
        <Logo />
        <span className="eyebrow">ADMINISTRATOR PORTAL</span>
        <Button variant="exit" onClick={session.quickExit}>
          Quick Exit <LogOut size={17} />
        </Button>
      </header>
      <main id="main">
        <div className="safe-page-title">
          <div>
            <span className="eyebrow">ADMINISTRATOR</span>
            <h1>Manage organization accounts.</h1>
          </div>
        </div>
        {session.message && (
          <p role="alert" className="form-error">
            {session.message}
          </p>
        )}
        <div className="page-toolbar">
          <Field
            label="Search accounts"
            value={search}
            onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
              setSearch(event.target.value)
            }
          />
          <div className="toolbar-actions">
            <Button
              variant="outline"
              onClick={() => void session.perform(refresh)}
              disabled={session.busy}
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
              {accounts
                .filter((account) =>
                  `${account.username} ${account.fullName} ${account.role}`
                    .toLowerCase()
                    .includes(search.toLowerCase()),
                )
                .map((account) => (
                  <tr key={account.uid}>
                    <td>@{account.username}</td>
                    <td>{account.role}</td>
                    <td>{account.fullName || "—"}</td>
                    <td>{account.profileComplete ? "Complete" : "Needs setup"}</td>
                    <td>
                      <Button
                        variant="quiet"
                        disabled={account.protected || session.busy}
                        onClick={() => setRemoving(account)}
                      >
                        Delete
                      </Button>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
        <Button variant="quiet" onClick={() => void session.perform(session.signOut)}>
          Sign out
        </Button>
      </main>
      {creating && (
        <Modal title="Create a managed account" onClose={() => setCreating(false)}>
          <Form
            onSubmit={async (data) => {
              await session.perform(async () => {
                await createAccount(Object.fromEntries(data.entries()));
                setCreating(false);
                await refresh();
              });
            }}
          >
            <Field label="Username" name="username" required minLength={8} maxLength={24} />
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
            <Button type="submit" disabled={session.busy}>
              Create account
            </Button>
          </Form>
        </Modal>
      )}
      {removing && (
        <Modal title="Delete this account?" onClose={() => setRemoving(null)}>
          <p>
            This permanently deletes @{removing.username} and their cloud records. This
            cannot be undone.
          </p>
          <Button
            disabled={session.busy}
            onClick={() =>
              void session.perform(async () => {
                await deleteAccount(removing.uid);
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
