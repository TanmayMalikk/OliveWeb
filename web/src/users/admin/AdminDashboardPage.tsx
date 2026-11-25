// Admins: shows account totals, staff workload, emergency response, live map, escalated chats, and settings.
import { useEffect, useState } from "react";
import {
  AlertTriangle,
  Briefcase,
  ChevronDown,
  ChevronUp,
  LayoutDashboard,
  MapPin,
  MessageCircle,
  Plus,
  RefreshCw,
  Settings as SettingsIcon,
  ShieldCheck,
  Users,
} from "lucide-react";
import { BirthdayField } from "../../components/BirthdayField";
import {
  Button,
  Empty,
  Field,
  Form,
  Modal,
  shakeNamedFields,
} from "../../components/SharedComponents";
import type { Role } from "../../domain/DomainModels";
import { cloudMode } from "../../services/AccountService";
import { useSession } from "../../session/SessionProvider";
import {
  createAccount,
  deleteAccount,
  listAccounts,
  listStaffWorkloads,
  loadAdminSummary,
  type AdminDashboardSummary,
  type ManagedAccount,
} from "./AdminAccountApi";
import {
  observeAdminLiveLocations,
  observeEmergencyReports,
  type AdminLiveLocation,
  type EmergencyReport,
} from "../../services/EmergencyResponseService";
import { AdminEmergencyResponsePanel } from "./AdminEmergencyResponsePanel";
import { OrganizationLiveMap } from "../shared/OrganizationLiveMap";
import { OrganizationSettingsPanel } from "../shared/OrganizationSettingsPanel";
import OrganizationSupportInbox from "../shared/OrganizationSupportInbox";
import {
  loadPlan,
  staffConsultations,
  type SafetyPlan,
} from "../../services/SafetyPlanService";
import { ConsultationWorkspace } from "../staff/ConsultationWorkspace";
import {
  OrganizationWorkspace,
  WorkspaceShortcuts,
  type WorkspacePage,
} from "../shared/OrganizationWorkspace";

type AdminView =
  | "home"
  | "emergencies"
  | "accounts"
  | "staff"
  | "plans"
  | "messages"
  | "map"
  | "settings";

const pages: WorkspacePage<AdminView>[] = [
  {
    id: "home",
    label: "Overview",
    title: "Administrator overview",
    description:
      "Manage accounts, staff workload, emergency alerts, consultations, escalated chats, and live map.",
    icon: LayoutDashboard,
  },
  {
    id: "emergencies",
    label: "Emergencies",
    title: "Emergency response",
    description:
      "Review active emergency alerts, open encrypted report PDFs, and track live locations.",
    icon: AlertTriangle,
  },
  {
    id: "accounts",
    label: "Accounts",
    description: "Find and manage organization and personal accounts.",
    icon: Users,
  },
  {
    id: "staff",
    label: "Staff management",
    title: "Staff management",
    description:
      "Track each staff member's assigned safety-plan consultations and support chats.",
    icon: Briefcase,
  },
  {
    id: "plans",
    label: "Consultations",
    title: "Safety-plan consultations",
    description: "Review active and unassigned safety plans.",
    icon: ShieldCheck,
  },
  {
    id: "messages",
    label: "Escalation chats",
    description: "Review tickets escalated to administrators and reply securely.",
    icon: MessageCircle,
  },
  {
    id: "map",
    label: "Live map",
    title: "Organization live map",
    description:
      "Monitor live and last-known user coordinates shared with the organization.",
    icon: MapPin,
  },
  {
    id: "settings",
    label: "Settings",
    title: "Account settings",
    description: "Update your administrator contact details and password.",
    icon: SettingsIcon,
  },
];

const roleOrder: Role[] = ["ADMIN", "STAFF", "USER", "SAFE_CONTACT"];
const roleLabels: Record<Role, string> = {
  USER: "User",
  SAFE_CONTACT: "Safe contact",
  STAFF: "Staff",
  ADMIN: "Administrator",
};
const roleGroupLabels: Record<Role, string> = {
  ADMIN: "Administrators",
  STAFF: "Staff",
  USER: "Users",
  SAFE_CONTACT: "Safe contacts",
};

export default function AdminPortal() {
  const session = useSession();
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [summary, setSummary] = useState<AdminDashboardSummary | null>(null);
  const [staffWorkloads, setStaffWorkloads] = useState<ManagedAccount[]>([]);
  const [staffLoaded, setStaffLoaded] = useState(false);
  const [search, setSearch] = useState("");
  const [profileStatus, setProfileStatus] = useState<"all" | "complete" | "required">("all");
  const [expandedRoles, setExpandedRoles] = useState<Record<Role, boolean>>({
    ADMIN: true,
    STAFF: true,
    USER: true,
    SAFE_CONTACT: true,
  });
  const [creating, setCreating] = useState(false);
  const [creatingBusy, setCreatingBusy] = useState(false);
  const [createRole, setCreateRole] = useState<Role>("USER");
  const [removing, setRemoving] = useState<ManagedAccount | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [plans, setPlans] = useState<
    {
      ownerUid: string;
      ownerName: string;
      updatedAt: number;
      ticketNumber: number;
      staffUid?: string;
      staffName?: string;
    }[]
  >([]);
  const [selectedPlan, setSelectedPlan] = useState<SafetyPlan | null>(null);
  const [emergencies, setEmergencies] = useState<EmergencyReport[]>([]);
  const [emergenciesLoading, setEmergenciesLoading] = useState(cloudMode);
  const [emergenciesError, setEmergenciesError] = useState("");
  const [liveLocations, setLiveLocations] = useState<Record<string, AdminLiveLocation>>({});
  const [locationsLoading, setLocationsLoading] = useState(cloudMode);
  const [locationsError, setLocationsError] = useState("");
  const [mapFollowUid, setMapFollowUid] = useState<string | null>(null);
  const [view, setView] = useState<AdminView>("home");

  // Web admin: Reloads the latest account directory and summary totals.
  async function refresh() {
    const [nextAccounts, nextSummary] = await Promise.all([
      listAccounts(),
      loadAdminSummary().catch(() => null),
    ]);
    setAccounts(nextAccounts);
    if (nextSummary) setSummary(nextSummary);
  }

  async function refreshStaff() {
    const nextStaff = await listStaffWorkloads();
    setStaffWorkloads(nextStaff);
    setStaffLoaded(true);
  }

  useEffect(() => {
    void session.perform(async () => {
      await refresh();
      const value = await staffConsultations();
      setPlans(value.plans);
    });
  }, []);

  useEffect(() => {
    if (view === "staff" && !staffLoaded) {
      void session.perform(refreshStaff);
    }
  }, [view, staffLoaded]);

  useEffect(() => {
    if (!cloudMode) {
      setEmergenciesLoading(false);
      setLocationsLoading(false);
      return;
    }
    let active = true;
    let stopReports = () => {};
    let stopLocations = () => {};
    void observeEmergencyReports(
      (reports) => {
        if (!active) return;
        setEmergencies(reports);
        setEmergenciesLoading(false);
        setEmergenciesError("");
      },
      (message) => {
        if (!active) return;
        setEmergenciesLoading(false);
        setEmergenciesError(message);
      },
    )
      .then((stop) => {
        if (active) stopReports = stop;
        else stop();
      })
      .catch(() => {
        if (active) setEmergenciesLoading(false);
      });
    void observeAdminLiveLocations(
      (points) => {
        if (!active) return;
        setLiveLocations(points);
        setLocationsLoading(false);
        setLocationsError("");
      },
      (message) => {
        if (!active) return;
        setLocationsLoading(false);
        setLocationsError(message);
      },
    )
      .then((stop) => {
        if (active) stopLocations = stop;
        else stop();
      })
      .catch(() => {
        if (active) setLocationsLoading(false);
      });
    return () => {
      active = false;
      stopReports();
      stopLocations();
    };
  }, []);

  const activeEmergencyCount = emergencies.filter((item) => item.resolvedAt <= 0).length;

  const matchingAccounts = accounts.filter(
    (account) =>
      (profileStatus === "all" ||
        Boolean(account.profileComplete) === (profileStatus === "complete")) &&
      [account.username, account.fullName, account.email, account.role, roleLabels[account.role]]
        .join(" ")
        .toLowerCase()
        .includes(search.trim().toLowerCase()),
  );

  const allExpanded = roleOrder.every((role) => expandedRoles[role]);
  const toggleAllRoles = () => {
    const next = !allExpanded;
    setExpandedRoles({
      ADMIN: next,
      STAFF: next,
      USER: next,
      SAFE_CONTACT: next,
    });
  };

  const navigate = (next: AdminView) => {
    if (next !== "plans") setSelectedPlan(null);
    if (next !== "map") setMapFollowUid(null);
    setView(next);
  };

  const overviewCounts = {
    USER: summary?.counts.USER ?? accounts.filter((account) => account.role === "USER").length,
    SAFE_CONTACT:
      summary?.counts.SAFE_CONTACT ??
      accounts.filter((account) => account.role === "SAFE_CONTACT").length,
    STAFF: summary?.counts.STAFF ?? accounts.filter((account) => account.role === "STAFF").length,
    ADMIN: summary?.counts.ADMIN ?? accounts.filter((account) => account.role === "ADMIN").length,
  };

  return (
    <OrganizationWorkspace role="Administrator" pages={pages} view={view} onNavigate={navigate}>
      {view === "home" && (
        <>
          <section className="panel organization-overview">
            <h2>Organization overview</h2>
            <div className="organization-counts">
              {(["USER", "SAFE_CONTACT", "STAFF", "ADMIN"] as const).map((role) => (
                <article key={role}>
                  <strong>{overviewCounts[role]}</strong>
                  <span>{roleGroupLabels[role]}</span>
                </article>
              ))}
            </div>
            {summary && (
              <div className="organization-ticket-counts" aria-label="Support ticket totals">
                <article>
                  <strong>{summary.tickets.open}</strong>
                  <span>Open tickets</span>
                </article>
                <article>
                  <strong>{summary.tickets.closed}</strong>
                  <span>Closed tickets</span>
                </article>
              </div>
            )}
          </section>
          <section className="panel">
            <h2>Emergency response</h2>
            <p>
              {activeEmergencyCount > 0
                ? `${activeEmergencyCount} active emergency alert${activeEmergencyCount === 1 ? "" : "s"} awaiting review.`
                : "No active emergency alerts."}{" "}
              This dashboard does not dispatch emergency services.
            </p>
            <div className="toolbar-actions" style={{ marginTop: "0.75rem" }}>
              <Button onClick={() => navigate("emergencies")}>Open emergency response</Button>
              <Button variant="outline" onClick={() => navigate("map")}>
                Open live map
              </Button>
              <Button variant="outline" onClick={() => navigate("messages")}>
                Open escalation chats
              </Button>
            </div>
          </section>
          <WorkspaceShortcuts pages={pages.slice(1)} onNavigate={navigate} />
        </>
      )}

      {view === "emergencies" && (
        <AdminEmergencyResponsePanel
          alerts={emergencies}
          locations={liveLocations}
          loading={emergenciesLoading}
          error={emergenciesError}
          onTrackOnMap={(ownerUid) => {
            setMapFollowUid(ownerUid);
            setView("map");
          }}
        />
      )}

      {view === "accounts" && (
        <>
          <div className="panel organization-toolbar account-toolbar">
            <Field
              label="Search accounts"
              type="search"
              value={search}
              onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                setSearch(event.target.value)
              }
            />
            <label>
              Profile status
              <select
                value={profileStatus}
                onChange={(event) => {
                  const value = event.currentTarget.value;
                  setProfileStatus(
                    value === "complete" || value === "required" ? value : "all",
                  );
                }}
              >
                <option value="all">All statuses</option>
                <option value="complete">Setup complete</option>
                <option value="required">Setup required</option>
              </select>
            </label>
            <div className="toolbar-actions">
              <Button variant="outline" onClick={toggleAllRoles}>
                {allExpanded ? "Collapse all" : "Expand all"}
              </Button>
              <Button
                variant="outline"
                onClick={() => void session.perform(refresh)}
                disabled={session.busy}
              >
                <RefreshCw size={16} /> Refresh
              </Button>
              <Button
                onClick={() => {
                  setCreateRole("USER");
                  setCreating(true);
                }}
              >
                <Plus size={17} /> Create account
              </Button>
            </div>
          </div>

          <div
            className="account-results"
            role="region"
            aria-label="Account results"
            tabIndex={0}
          >
            {matchingAccounts.length === 0 ? (
              <div className="panel">
                <p>{session.busy ? "Loading accounts…" : "No matching accounts."}</p>
              </div>
            ) : (
              roleOrder.map((role) => {
                const roleAccounts = matchingAccounts.filter(
                  (account) => account.role === role,
                );
                const expanded = expandedRoles[role];
                return (
                  <section className="panel account-role-group" key={role}>
                    <button
                      type="button"
                      className="account-role-toggle"
                      aria-expanded={expanded}
                      onClick={() =>
                        setExpandedRoles((current) => ({
                          ...current,
                          [role]: !current[role],
                        }))
                      }
                    >
                      <span>
                        <strong>{roleGroupLabels[role]}</strong>{" "}
                        <span className="tag">{roleAccounts.length}</span>
                      </span>
                      {expanded ? (
                        <ChevronUp size={18} aria-hidden="true" />
                      ) : (
                        <ChevronDown size={18} aria-hidden="true" />
                      )}
                    </button>
                    {expanded &&
                      (roleAccounts.length === 0 ? (
                        <p className="account-role-empty">
                          No matching accounts in this category.
                        </p>
                      ) : (
                        <div className="table-scroll organization-account-table">
                          <table>
                            <caption className="sr-only">
                              {roleGroupLabels[role]} managed accounts
                            </caption>
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
                              {roleAccounts.map((account) => (
                                <tr key={account.uid}>
                                  <th scope="row">
                                    @{account.username}{" "}
                                    {account.protected && (
                                      <span className="tag">Protected</span>
                                    )}
                                  </th>
                                  <td>
                                    <span className="tag">{roleLabels[account.role]}</span>
                                  </td>
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
                      ))}
                  </section>
                );
              })
            )}
          </div>
        </>
      )}

      {view === "staff" && (
        <>
          <div className="panel organization-toolbar">
            <div>
              <h2>Staff workload</h2>
              <p>Assigned users, safety-plan consultations, and support conversations by staff account.</p>
            </div>
            <Button
              variant="outline"
              onClick={() => void session.perform(refreshStaff)}
              disabled={session.busy}
            >
              <RefreshCw size={16} aria-hidden="true" /> Refresh
            </Button>
          </div>
          {staffWorkloads.length === 0 ? (
            <Empty title={session.busy ? "Loading staff accounts…" : "No staff accounts found."}>
              Create a Staff account from the Accounts section to assign consultations and support tickets.
            </Empty>
          ) : (
            <div className="staff-workload-grid">
              {staffWorkloads.map((member) => (
                <article key={member.uid} className="panel staff-workload-card">
                  <div className="staff-workload-head">
                    <div>
                      <h3>@{member.username}</h3>
                      {member.fullName && <p>{member.fullName}</p>}
                    </div>
                    <span className="tag">
                      {member.assignedUsers ?? 0} active user
                      {(member.assignedUsers ?? 0) === 1 ? "" : "s"}
                    </span>
                  </div>
                  <dl className="staff-workload-rows">
                    <div className="staff-workload-row">
                      <dt>Safety-plan tasks</dt>
                      <dd>
                        {member.safetyPlans ?? 0} ({member.completedSafetyPlans ?? 0} completed)
                      </dd>
                    </div>
                    <div className="staff-workload-row">
                      <dt>Support chat tasks</dt>
                      <dd>
                        {member.supportChats ?? 0} ({member.closedSupportChats ?? 0} closed)
                      </dd>
                    </div>
                  </dl>
                </article>
              ))}
            </div>
          )}
        </>
      )}

      {view === "plans" && (
        <>
          <div className="panel organization-toolbar">
            <h2>Consultation queue</h2>
            <Button
              variant="outline"
              disabled={session.busy}
              onClick={() =>
                void session.perform(async () => setPlans((await staffConsultations()).plans))
              }
            >
              <RefreshCw size={16} aria-hidden="true" /> Refresh
            </Button>
          </div>
          <ConsultationWorkspace
            role="ADMIN"
            items={plans}
            plan={selectedPlan}
            busy={session.busy}
            onChange={setSelectedPlan}
            onOpen={(ownerUid) =>
              void session.perform(async () => setSelectedPlan(await loadPlan(ownerUid)))
            }
          />
        </>
      )}

      {view === "messages" && <OrganizationSupportInbox />}

      {view === "map" && (
        <OrganizationLiveMap
          locations={liveLocations}
          loading={locationsLoading}
          error={locationsError}
          initialFollowUid={mapFollowUid}
          onClearInitialFollow={() => setMapFollowUid(null)}
        />
      )}

      {view === "settings" && <OrganizationSettingsPanel />}

      {creating && (
        <Modal
          title="Create a managed account"
          onClose={() => {
            if (!creatingBusy) setCreating(false);
          }}
        >
          <Form
            onSubmit={async (data, form) => {
              await session.perform(async () => {
                setCreatingBusy(true);
                try {
                  try {
                    await createAccount(Object.fromEntries(data.entries()));
                  } catch (failure) {
                    const msg = failure instanceof Error ? failure.message.toLowerCase() : "";
                    if (msg.includes("email")) shakeNamedFields(form, ["email"]);
                    else if (msg.includes("username")) shakeNamedFields(form, ["username"]);
                    else if (msg.includes("password")) shakeNamedFields(form, ["password"]);
                    else if (msg.includes("birth") || msg.includes("date"))
                      shakeNamedFields(form, ["dob"]);
                    else shakeNamedFields(form, ["username", "password"]);
                    throw failure;
                  }
                  setCreating(false);
                  await refresh();
                } finally {
                  setCreatingBusy(false);
                }
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
              <select
                name="role"
                value={createRole}
                onChange={(event) => setCreateRole(event.currentTarget.value as Role)}
              >
                <option value="USER">User</option>
                <option value="SAFE_CONTACT">Safe contact</option>
                <option value="STAFF">Staff</option>
                <option value="ADMIN">Administrator</option>
              </select>
            </label>
            <Field label="Full name" name="fullName" maxLength={120} />
            <BirthdayField />
            {createRole === "STAFF" || createRole === "ADMIN" ? (
              <>
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
                <Field label="Phone (optional)" name="phone" maxLength={40} />
              </>
            ) : (
              <>
                <Field label="City" name="region" maxLength={160} />
                {createRole === "USER" && (
                  <>
                    <Field
                      label="People affected (optional)"
                      name="affectedNames"
                      maxLength={500}
                    />
                    <Field
                      label="Person causing harm (optional)"
                      name="abuserName"
                      maxLength={120}
                    />
                    <label className="field">
                      <span>Situation (optional)</span>
                      <textarea name="situation" maxLength={2000} />
                    </label>
                  </>
                )}
              </>
            )}
            {creatingBusy && (
              <div className="modal-loading" role="status" aria-live="polite">
                <div className="modal-loading-bar" role="progressbar" aria-label="Creating managed account">
                  <span className="modal-loading-bar-fill" />
                </div>
                <p className="modal-loading-text">Creating managed account…</p>
              </div>
            )}
            {session.message && (
              <p role="alert" className="form-error">{session.message}</p>
            )}
            <div className="modal-actions">
              <Button type="submit" disabled={creatingBusy || session.busy}>
                {creatingBusy ? "Creating account…" : "Create account"}
              </Button>
              <Button
                variant="quiet"
                disabled={creatingBusy}
                onClick={() => setCreating(false)}
              >
                Cancel
              </Button>
            </div>
          </Form>
        </Modal>
      )}

      {removing && (
        <Modal
          title="Delete this account?"
          onClose={() => {
            if (!deleting) setRemoving(null);
          }}
        >
          <p>
            This permanently deletes @{removing.username} and their cloud
            records. This cannot be undone.
          </p>
          {deleting && (
            <div className="modal-loading" role="status" aria-live="polite">
              <div
                className="modal-loading-bar"
                role="progressbar"
                aria-label={`Deleting @${removing.username}`}
              >
                <span className="modal-loading-bar-fill" />
              </div>
              <p className="modal-loading-text">
                Deleting @{removing.username} and cloud records…
              </p>
            </div>
          )}
          {session.message && (
            <p role="alert" className="form-error">{session.message}</p>
          )}
          <div className="modal-actions">
            <Button
              disabled={deleting || session.busy}
              onClick={() =>
                void session.perform(async () => {
                  setDeleting(true);
                  try {
                    await deleteAccount(removing.uid);
                    setRemoving(null);
                    await refresh();
                  } finally {
                    setDeleting(false);
                  }
                })
              }
            >
              {deleting ? `Deleting @${removing.username}…` : `Delete @${removing.username}`}
            </Button>
            <Button
              variant="quiet"
              disabled={deleting}
              onClick={() => setRemoving(null)}
            >
              Cancel
            </Button>
          </div>
        </Modal>
      )}
    </OrganizationWorkspace>
  );
}
