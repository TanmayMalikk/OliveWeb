// Admins: shows account totals and controls to admins.
import { useEffect, useState } from "react";
import { LayoutDashboard, MessageCircle, Plus, RefreshCw, ShieldCheck, Users } from "lucide-react";
import {
  Button,
  Field,
  Form,
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
import { ConsultationWorkspace } from "../staff/ConsultationWorkspace";
import { OrganizationWorkspace, WorkspaceShortcuts, type WorkspacePage } from "../shared/OrganizationWorkspace";

type AdminView = "home" | "plans" | "accounts" | "messages";
const pages: WorkspacePage<AdminView>[] = [
  { id: "home", label: "Overview", title: "Administrator overview", description: "Manage accounts, consultations, and escalated support tickets.", icon: LayoutDashboard },
  { id: "accounts", label: "Accounts", description: "Find and manage organization and personal accounts.", icon: Users },
  { id: "plans", label: "Consultations", title: "Safety-plan consultations", description: "Open assigned safety plans for review.", icon: ShieldCheck },
  { id: "messages", label: "Escalation chats", description: "Review support tickets and respond to escalations.", icon: MessageCircle },
];
const roleLabels = { USER: "User", SAFE_CONTACT: "Safe contact", STAFF: "Staff", ADMIN: "Administrator" };

export default function AdminPortal() {
  const session = useSession();
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<ManagedAccount | null>(null);
  const [plans, setPlans] = useState<
    { ownerUid: string; ownerName: string; updatedAt: number; ticketNumber: number }[]
  >([]);
  const [selectedPlan, setSelectedPlan] = useState<SafetyPlan | null>(null);
  const [view, setView] = useState<AdminView>("home");

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

  const matchingAccounts = accounts.filter((account) =>
    [account.username, account.fullName, account.role, roleLabels[account.role]]
      .join(" ").toLowerCase().includes(search.trim().toLowerCase()),
  );

  const navigate = (next: AdminView) => {
    if (next !== "plans") setSelectedPlan(null);
    setView(next);
  };

  return (
    <OrganizationWorkspace role="Administrator" pages={pages} view={view} onNavigate={navigate}>
      {view === "home" && <>
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
        <WorkspaceShortcuts pages={pages.slice(1)} onNavigate={navigate} />
      </>}
      {view === "accounts" && <>
        <div className="panel organization-toolbar account-toolbar">
          <Field
            label="Search accounts"
            type="search"
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
        <div className="table-scroll panel organization-account-table" role="region" aria-label="Account results" tabIndex={0}>
          <table>
            <caption className="sr-only">Managed accounts</caption>
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
              {matchingAccounts.map((account) => (
                <tr key={account.uid}>
                    <th scope="row">@{account.username}</th>
                    <td><span className="tag">{roleLabels[account.role]}</span></td>
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
              {matchingAccounts.length === 0 && (
                <tr><td colSpan={5}>{session.busy ? "Loading accounts…" : "No matching accounts."}</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </>}
      {view === "plans" && <>
        <div className="panel organization-toolbar">
          <h2>Consultation queue</h2>
          <Button variant="outline" disabled={session.busy} onClick={() =>
            void session.perform(async () => setPlans((await staffConsultations()).plans))
          }>
            <RefreshCw size={16} aria-hidden="true" /> Refresh
          </Button>
        </div>
        <ConsultationWorkspace items={plans} plan={selectedPlan} busy={session.busy} onChange={setSelectedPlan}
          onOpen={(ownerUid) => void session.perform(async () => setSelectedPlan(await loadPlan(ownerUid)))} />
      </>}
      {view === "messages" && <OrganizationSupportInbox />}
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
    </OrganizationWorkspace>
  );
}
