// Staff: shows assigned users and support chats to staff.
import { useEffect, useState } from "react";
import { LayoutDashboard, MessageCircle, ShieldCheck } from "lucide-react";
import { Button } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import { accounts } from "../../services/AccountService";
import { register } from "../../services/MessagingService";
import {
  loadPlan,
  observeSafetyAccess,
  setStaffAvailability,
  staffConsultations,
  type SafetyPlan,
} from "../../services/SafetyPlanService";
import { ConsultationWorkspace } from "./ConsultationWorkspace";
import { OrganizationWorkspace, WorkspaceShortcuts, type WorkspacePage } from "../shared/OrganizationWorkspace";
import OrganizationSupportInbox from "../shared/OrganizationSupportInbox";

type StaffView = "home" | "plans" | "messages";
const pages: WorkspacePage<StaffView>[] = [
  { id: "home", label: "Overview", title: "Staff overview", description: "Manage your consultation availability and respond to assigned tickets.", icon: LayoutDashboard },
  { id: "plans", label: "Consultations", title: "Safety-plan consultations", description: "Review and update the plans assigned to you.", icon: ShieldCheck },
  { id: "messages", label: "Support chats", description: "Read and reply to your assigned tickets.", icon: MessageCircle },
];

export default function StaffPortal() {
  const session = useSession();
  const [consultations, setConsultations] = useState<
    { ownerUid: string; ownerName: string; updatedAt: number; ticketNumber: number }[]
  >([]);
  const [available, setAvailable] = useState(true);
  const [plan, setPlan] = useState<SafetyPlan | null>(null);
  const [view, setView] = useState<StaffView>("home");

  // Reload staff tickets when the signed-in session changes.
  const identity = accounts.identity;
  const uid = identity?.uid;
  useEffect(() => {
    if (!uid) return;
    let active = true;
    let stop: (() => void) | undefined;
    let request = 0;
    const refresh = () => {
      const token = ++request;
      void staffConsultations().then((value) => {
        if (!active || token !== request) return;
        setConsultations(value.plans);
        setAvailable(value.available);
      }).catch(() => undefined);
    };
    refresh();
    if (identity) void register(identity).catch(() => undefined);
    void observeSafetyAccess(uid, refresh, () => undefined).then((cleanup) => {
      if (active) stop = cleanup;
      else cleanup();
    });
    return () => { active = false; stop?.(); };
  }, [uid]);

  const availability = (
    <section className="panel organization-toolbar">
      <div>
        <h2>Consultation availability</h2>
        <p>{available ? "Available for new requests" : "Not accepting new requests"}</p>
      </div>
      <Button variant="outline" disabled={session.busy} onClick={() =>
        void session.perform(async () => {
          const value = await setStaffAvailability(!available);
          setAvailable(value.available);
        })
      }>
        {available ? "Pause availability" : "Become available"}
      </Button>
    </section>
  );

  const navigate = (next: StaffView) => {
    if (next !== "plans") setPlan(null);
    setView(next);
  };

  return (
    <OrganizationWorkspace role="Staff" pages={pages} view={view} onNavigate={navigate}>
      {view === "home" && <>
        {availability}
        <WorkspaceShortcuts pages={pages.slice(1)} onNavigate={navigate} />
      </>}
      {view === "plans" && <>
        {availability}
        <ConsultationWorkspace items={consultations} plan={plan} busy={session.busy} onChange={setPlan}
          onOpen={(ownerUid) => void session.perform(async () => setPlan(await loadPlan(ownerUid)))}
          onCompleted={() => setConsultations((current) => current.filter((item) => item.ownerUid !== plan?.ownerUid))} />
      </>}
      {view === "messages" && <OrganizationSupportInbox />}
    </OrganizationWorkspace>
  );
}
