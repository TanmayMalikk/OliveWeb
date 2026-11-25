// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyProfile } from "../../domain/DomainModels";
import type { ManagedAccount, AdminDashboardSummary } from "./AdminAccountApi";
import type { AdminLiveLocation, EmergencyReport } from "../../services/EmergencyResponseService";
import type { BrowserDocument } from "../../services/MediaService";
import { installDomMocks, renderWithSession } from "../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "admin-1", username: "admin_one", role: "ADMIN" as const },
  cloudMode: true,
  changePassword: vi.fn(async (..._args: unknown[]) => {}),
  listAccounts: vi.fn(async (): Promise<ManagedAccount[]> => []),
  loadAdminSummary: vi.fn(async (): Promise<AdminDashboardSummary> => ({
    counts: { USER: 3, SAFE_CONTACT: 1, STAFF: 2, ADMIN: 1 },
    tickets: { open: 4, closed: 2 },
  })),
  listStaffWorkloads: vi.fn(async (): Promise<ManagedAccount[]> => []),
  createAccount: vi.fn(async (..._args: unknown[]) => {}),
  deleteAccount: vi.fn(async (..._args: unknown[]) => {}),
  observeEmergencyReports: vi.fn(async (..._args: unknown[]) => () => {}),
  observeAdminLiveLocations: vi.fn(async (..._args: unknown[]) => () => {}),
  openEmergencyReportPdf: vi.fn(async (..._args: unknown[]): Promise<{ document: BrowserDocument; url: string }> => ({
    document: {
      id: "pdf-1",
      name: "report.pdf",
      mimeType: "application/pdf",
      ownerUid: "u1",
      createdAt: 100,
      approved: true,
      approvalMessage: "",
      ciphertextSize: 100,
      ciphertextSha256: "a".repeat(64),
      evidence: true,
    },
    url: "blob:pdf-1",
  })),
  setEmergencyResolved: vi.fn(async (..._args: unknown[]) => {}),
  saveOrganizationProfile: vi.fn(async (..._args: unknown[]) => {}),
  staffConsultations: vi.fn(async () => ({
    plans: [
      { ownerUid: "u1", ownerName: "Alex", updatedAt: 1000, ticketNumber: 7, staffUid: "s1", staffName: "Staff One" },
    ],
  })),
  loadPlan: vi.fn(async (..._args: unknown[]) => ({
    ownerUid: "u1",
    ownerName: "Alex",
    fields: {},
    consultationRequested: true,
    staffUid: "s1",
    safeContactUids: [],
    pendingConflicts: {},
  })),
}));

vi.mock("../../services/LocalDataStore", () => ({
  database: Promise.resolve({ get: vi.fn(), put: vi.fn(), delete: vi.fn() }),
}));

vi.mock("../../session/SessionProvider", async () => {
  const { useSession } = await import("../../session/SessionContext");
  return { useSession };
});

vi.mock("../../services/AccountService", () => ({
  get cloudMode() {
    return mocks.cloudMode;
  },
  accounts: {
    get identity() {
      return mocks.identity;
    },
    changePassword: (...args: unknown[]) => mocks.changePassword(...args),
  },
}));

vi.mock("./AdminAccountApi", () => ({
  listAccounts: () => mocks.listAccounts(),
  loadAdminSummary: () => mocks.loadAdminSummary(),
  listStaffWorkloads: () => mocks.listStaffWorkloads(),
  createAccount: (...args: unknown[]) => mocks.createAccount(...args),
  deleteAccount: (...args: unknown[]) => mocks.deleteAccount(...args),
}));

vi.mock("../../services/EmergencyResponseService", () => ({
  observeEmergencyReports: (...args: unknown[]) => mocks.observeEmergencyReports(...args),
  observeAdminLiveLocations: (...args: unknown[]) => mocks.observeAdminLiveLocations(...args),
  openEmergencyReportPdf: (...args: unknown[]) => mocks.openEmergencyReportPdf(...args),
  setEmergencyResolved: (...args: unknown[]) => mocks.setEmergencyResolved(...args),
}));

vi.mock("../../services/OrganizationProfileService", () => ({
  saveOrganizationProfile: (...args: unknown[]) => mocks.saveOrganizationProfile(...args),
}));

vi.mock("../../services/SafetyPlanService", () => ({
  staffConsultations: () => mocks.staffConsultations(),
  loadPlan: (...args: unknown[]) => mocks.loadPlan(...args),
}));

vi.mock("../shared/OrganizationLiveMap", () => ({
  OrganizationLiveMap: ({ initialFollowUid }: { initialFollowUid?: string | null }) => (
    <div data-testid="org-live-map-stub">Follow:{initialFollowUid ?? "none"}</div>
  ),
}));

vi.mock("../shared/OrganizationSupportInbox", () => ({
  default: () => <div data-testid="org-inbox-stub">InboxStub</div>,
}));

vi.mock("../staff/ConsultationWorkspace", () => ({
  ConsultationWorkspace: ({ items, onOpen }: { items: Array<{ ownerUid: string; ticketNumber: number }>; onOpen: (uid: string) => void }) => (
    <div>
      {items.map((item) => (
        <button key={item.ownerUid} type="button" onClick={() => onOpen(item.ownerUid)}>
          Consult #{item.ticketNumber}
        </button>
      ))}
    </div>
  ),
}));

import AdminPortal from "./AdminDashboardPage";
import { AdminEmergencyResponsePanel } from "./AdminEmergencyResponsePanel";
import { OrganizationSettingsPanel } from "../shared/OrganizationSettingsPanel";

const sampleAccounts: ManagedAccount[] = [
  { uid: "admin-1", username: "admin_one", role: "ADMIN", fullName: "Admin One", email: "a@org.ca", phone: "", profileComplete: true, protected: true },
  { uid: "staff-1", username: "staff_one", role: "STAFF", fullName: "Staff One", email: "s@org.ca", phone: "", profileComplete: true, protected: false, assignedUsers: 2, safetyPlans: 3, completedSafetyPlans: 1, supportChats: 4, closedSupportChats: 2 },
  { uid: "user-1", username: "user_one", role: "USER", fullName: "Alex Rivera", email: "", phone: "", profileComplete: false, protected: false },
];

const alertsFixture: EmergencyReport[] = [
  { id: "e1", ownerUid: "user-1", ownerName: "Alex Rivera", mediaId: "pdf-1", status: "ACTIVE", resolvedBy: "", createdAt: 1700000000000, resolvedAt: 0 },
  { id: "e2", ownerUid: "user-2", ownerName: "Sam Lee", mediaId: "", status: "RESOLVED", resolvedBy: "admin-1", createdAt: 1699900000000, resolvedAt: 1700005000000 },
];

const locationsFixture: Record<string, AdminLiveLocation> = {
  "user-1": { uid: "user-1", name: "Alex Rivera", latitude: 53.54, longitude: -113.49, accuracy: 12, active: true, updatedAt: 1700000000000 },
};

describe("AdminDashboardPage, AdminEmergencyResponsePanel, OrganizationSettingsPanel, and OrganizationWorkspace", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.cloudMode = true;
    mocks.listAccounts.mockResolvedValue(sampleAccounts);
    mocks.listStaffWorkloads.mockResolvedValue([sampleAccounts[1]]);
    mocks.observeEmergencyReports.mockImplementation(async (onData) => {
      (onData as (r: EmergencyReport[]) => void)(alertsFixture);
      return () => {};
    });
    mocks.observeAdminLiveLocations.mockImplementation(async (onData) => {
      (onData as (l: Record<string, AdminLiveLocation>) => void)(locationsFixture);
      return () => {};
    });
  });

  afterEach(() => {
    cleanup();
  });

  it("drives OrganizationSettingsPanel profile validation, cloud/local saves, and password changes", async () => {
    const refresh = vi.fn(async () => {});
    const saveProfile = vi.fn(async () => {});
    const clearMessage = vi.fn();

    const emptyView = renderWithSession(<OrganizationSettingsPanel />, { profile: null });
    expect(screen.getByText("Account details unavailable")).toBeTruthy();
    emptyView.unmount();

    const adminProfile = {
      ...emptyProfile("admin_one", "ADMIN"),
      fullName: "Admin One",
      dob: "1990-01-01",
      email: "admin@example.com",
    };

    renderWithSession(<OrganizationSettingsPanel />, { profile: adminProfile, refresh, saveProfile, clearMessage });

    // Invalid email triggers validation shake, valid email saves in cloudMode.
    fireEvent.change(screen.getByLabelText("Contact email"), { target: { value: "bad" } });
    fireEvent.submit(screen.getByRole("button", { name: /Save profile/i }).closest("form")!);
    fireEvent.change(screen.getByLabelText("Contact email"), { target: { value: "admin@olive.org" } });
    fireEvent.submit(screen.getByRole("button", { name: /Save profile/i }).closest("form")!);
    await waitFor(() => expect(mocks.saveOrganizationProfile).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText("Profile details updated.")).toBeTruthy());

    // Local mode save branch.
    mocks.cloudMode = false;
    fireEvent.submit(screen.getByRole("button", { name: /Save profile/i }).closest("form")!);
    await waitFor(() => expect(saveProfile).toHaveBeenCalled());

    // Password change error + success.
    const pwdForm = screen.getByRole("button", { name: "Change password" }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Current password"), { target: { value: "OldPass1!" } });
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "admin_one1!" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "admin_one1!" } });
    fireEvent.submit(pwdForm); // contains username

    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "ValidNew1!" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "ValidNew1!" } });
    mocks.changePassword.mockRejectedValueOnce(new Error("Wrong current password"));
    fireEvent.submit(pwdForm);
    await waitFor(() => expect(mocks.changePassword).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Current password"), { target: { value: "OldPass1!" } });
    fireEvent.change(screen.getByLabelText("New password"), { target: { value: "ValidNew1!" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "ValidNew1!" } });
    fireEvent.submit(pwdForm);
    await waitFor(() => expect(screen.getByText("Password updated.")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
  });

  it("drives AdminEmergencyResponsePanel active/resolved tabs, PDF open/error, inline tracking, and resolve toggle", async () => {
    renderWithSession(
      <AdminEmergencyResponsePanel
        alerts={alertsFixture}
        locations={locationsFixture}
        loading={false}
        error="Minor warning"
      />,
    );

    expect(screen.getByText("Minor warning")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Alex Rivera" })).toBeTruthy();

    // Open PDF failure then success, toggle inline live tracking, mark resolved.
    mocks.openEmergencyReportPdf.mockRejectedValueOnce(new Error("PDF locked"));
    fireEvent.click(screen.getByRole("button", { name: /Open report PDF/i }));
    await waitFor(() => expect(screen.getByText("PDF locked")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /Open report PDF/i }));
    await waitFor(() => expect(screen.getByTitle("report.pdf")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    fireEvent.click(screen.getByRole("button", { name: /Track live location/i }));
    expect(screen.getByText(/Live sharing active/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Mark as resolved/i }));
    await waitFor(() => expect(mocks.setEmergencyResolved).toHaveBeenCalledWith("e1", true));

    // Switch to Resolved tab and reopen alert.
    fireEvent.click(screen.getByRole("tab", { name: /Resolved \(1\)/i }));
    expect(screen.getByText("Sam Lee")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Reopen/i }));
    await waitFor(() => expect(mocks.setEmergencyResolved).toHaveBeenCalledWith("e2", false));
  });

  it("drives AdminDashboardPage views, account create/delete modals, workloads, consultations, map, and settings", async () => {
    const signOut = vi.fn(async () => {});
    const profile = { ...emptyProfile("admin_one", "ADMIN"), fullName: "Admin One", dob: "1990-01-01", email: "a@b.ca" };

    renderWithSession(<AdminPortal />, { profile, signOut, message: "Portal notice" });
    await waitFor(() => expect(screen.getByText("Open tickets")).toBeTruthy());
    expect(screen.getByText(/1 active emergency alert awaiting review/i)).toBeTruthy();

    // Navigate Emergencies -> Track on map -> live map follows user-1.
    fireEvent.click(screen.getByRole("button", { name: /Open emergency response/i }));
    fireEvent.click(screen.getAllByRole("button", { name: /Track live location/i })[0]);
    expect(screen.getByTestId("org-live-map-stub").textContent).toBe("Follow:user-1");

    // Navigate Accounts: filter, toggle groups, create managed Staff account (error + success), delete account.
    fireEvent.click(screen.getByRole("button", { name: "Accounts" }));
    await waitFor(() => expect(screen.getByText("@user_one")).toBeTruthy());
    fireEvent.change(screen.getByLabelText("Profile status"), { target: { value: "required" } });
    fireEvent.change(screen.getByLabelText("Profile status"), { target: { value: "all" } });
    fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));
    fireEvent.click(screen.getByRole("button", { name: "Expand all" }));

    fireEvent.click(screen.getByRole("button", { name: /Create account/i }));
    fireEvent.change(screen.getByLabelText("Role"), { target: { value: "STAFF" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "new_staff_1" } });
    fireEvent.change(screen.getByLabelText("Temporary password"), { target: { value: "TempPass1!" } });
    mocks.createAccount.mockRejectedValueOnce(new Error("Invalid email address"));
    fireEvent.submit(screen.getAllByRole("button", { name: "Create account" }).at(-1)!.closest("form")!);
    await waitFor(() => expect(mocks.createAccount).toHaveBeenCalledTimes(1));

    const modalCreateBtn = screen.getAllByRole("button", { name: "Create account" }).at(-1)!;
    fireEvent.submit(modalCreateBtn.closest("form")!);
    await waitFor(() => expect(mocks.createAccount).toHaveBeenCalledTimes(2));

    fireEvent.click(screen.getAllByRole("button", { name: "Delete" }).find((b) => !(b as HTMLButtonElement).disabled)!);
    await waitFor(() => expect(screen.getByText("Delete this account?")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /Delete @staff_one|Delete @user_one/i }));
    await waitFor(() => expect(mocks.deleteAccount).toHaveBeenCalled());

    // Staff workloads view, Consultations view, Escalation inbox view, Settings view, Sign out.
    fireEvent.click(screen.getByRole("button", { name: "Staff management" }));
    await waitFor(() => expect(screen.getByText("Safety-plan tasks")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Consultations" }));
    fireEvent.click(screen.getByRole("button", { name: "Consult #7" }));
    await waitFor(() => expect(mocks.loadPlan).toHaveBeenCalledWith("u1"));

    fireEvent.click(screen.getByRole("button", { name: "Escalation chats" }));
    expect(screen.getByTestId("org-inbox-stub")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Settings" }));
    expect(screen.getByText("Profile details")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Sign out/i }));
    expect(signOut).toHaveBeenCalled();

    // Error observers + summary fallback + local-mode fallback branches.
    cleanup();
    mocks.loadAdminSummary.mockRejectedValueOnce(new Error("Summary unavailable"));
    mocks.observeEmergencyReports.mockImplementationOnce(async (_onData, onErr) => {
      (onErr as (m: string) => void)("Alerts stream paused");
      return () => {};
    });
    mocks.observeAdminLiveLocations.mockImplementationOnce(async (_onData, onErr) => {
      (onErr as (m: string) => void)("Locations stream paused");
      return () => {};
    });
    renderWithSession(<AdminPortal />, { profile });
    await waitFor(() => expect(screen.getByText("Organization overview")).toBeTruthy());

    cleanup();
    mocks.cloudMode = false;
    renderWithSession(<AdminPortal />, { profile });
    await waitFor(() => expect(screen.getByText("Open tickets")).toBeTruthy());
  });
});
