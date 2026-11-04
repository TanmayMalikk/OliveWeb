// Admins: shows account totals and controls to admins.
import { useEffect, useState } from "react";
import { LogOut, Plus, RefreshCw } from "lucide-react";
import {
  Button,
  Field,
  Form,
  Logo,
  Modal,
} from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import {
  createAccount,
  deleteAccount,
  listAccounts,
  type ManagedAccount,
} from "./AdminAccountApi";
import OrganizationSupportInbox from "../shared/OrganizationSupportInbox";
import {
  staffConsultations,
  loadPlan,
  type SafetyPlan,
} from "../../services/SafetyPlanService";
import SafetyPlanEditor from "../staff/StaffSafetyPlanEditor";

export default function AdminPortal() {
  const session = useSession();
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<ManagedAccount | null>(null);
  const [plans, setPlans] = useState<
    { ownerUid: string; ownerName: string; updatedAt: number }[]
  >([]);
  const [selectedPlan, setSelectedPlan] = useState<SafetyPlan | null>(null);

  // Web admin: Reloads the latest data.
  async function refresh() {
    setAccounts(await listAccounts());
  }

  useEffect(() => {
    void session.perform(async () => {
      await refresh();
      const value = await staffConsultations();
      setPlans(value.plans);
    });
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
        <section className="panel organization-overview">
          <h2>Organization overview</h2>
          <div className="organization-counts">
            {(["USER", "SAFE_CONTACT", "STAFF", "ADMIN"] as const).map(
              (role) => (
                <article key={role}>
                  <strong>
                    {accounts.filter((account) => account.role === role).length}
                  </strong>
                  <span>
                    {role === "SAFE_CONTACT"
                      ? "Safe contacts"
                      : role === "ADMIN"
                        ? "Admins"
                        : role === "STAFF"
                          ? "Staff"
                          : "Users"}
                  </span>
                </article>
              ),
            )}
          </div>
        </section>
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
                    <td>
                      {account.profileComplete ? "Complete" : "Needs setup"}
                    </td>
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
        <section className="panel organization-plans">
          <div className="organization-inbox-heading">
            <div>
              <span className="eyebrow">PLAN CONSULTS</span>
              <h2>Safety plans needing review</h2>
            </div>
            <Button
              variant="outline"
              disabled={session.busy}
              onClick={() =>
                void session.perform(async () => {
                  const value = await staffConsultations();
                  setPlans(value.plans);
                })
              }
            >
              <RefreshCw size={16} /> Refresh
            </Button>
          </div>
          {plans.length === 0 ? (
            <p className="help-note">No active plan consultations.</p>
          ) : (
            <div className="organization-plan-list">
              {plans.map((item) => (
                <Button
                  key={item.ownerUid}
                  variant="outline"
                  disabled={session.busy}
                  onClick={() =>
                    void session.perform(async () =>
                      setSelectedPlan(await loadPlan(item.ownerUid)),
                    )
                  }
                >
                  Open {item.ownerName || "shared plan"}
                </Button>
              ))}
            </div>
          )}
          {selectedPlan && (
            <SafetyPlanEditor plan={selectedPlan} onChange={setSelectedPlan} />
          )}
        </section>
        <OrganizationSupportInbox />
        <Button
          variant="quiet"
          onClick={() => void session.perform(session.signOut)}
        >
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
              await session.perform(async () => {
                await createAccount(Object.fromEntries(data.entries()));
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
              label="Login email (optional for staff and administrators)"
              name="email"
              type="email"
              maxLength={254}
            />
            <p className="field-help">
              If left blank, the login is username@yourolivebranch.ca. The
              login email cannot be changed after this account is created.
            </p>
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
            This permanently deletes @{removing.username} and their cloud
            records. This cannot be undone.
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
