// Staff: shows assigned users and support chats to staff.
import { useEffect, useState } from "react";
import { LogOut } from "lucide-react";
import { Button, Empty, Logo } from "../../components/SharedComponents";
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
import SafetyPlanEditor from "./StaffSafetyPlanEditor";
import OrganizationSupportInbox from "../shared/OrganizationSupportInbox";

export default function StaffPortal() {
  const session = useSession();
  const [consultations, setConsultations] = useState<
    { ownerUid: string; ownerName: string; updatedAt: number }[]
  >([]);
  const [available, setAvailable] = useState(true);
  const [plan, setPlan] = useState<SafetyPlan | null>(null);

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

  return (
    <div className="page-width private-shell portal">
      <header className="site-header">
        <Logo />
        <span className="eyebrow">STAFF PORTAL</span>
        <Button variant="exit" onClick={session.quickExit}>
          Quick Exit <LogOut size={17} />
        </Button>
      </header>
      <main id="main">
        <div className="safe-page-title">
          <div>
            <span className="eyebrow">STAFF</span>
            <h1>Safety-plan consultations.</h1>
          </div>
        </div>
        {session.message && (
          <p role="alert" className="form-error">
            {session.message}
          </p>
        )}
        <section className="panel safety-plan-summary">
          <div>
            <h2>Availability</h2>
            <p>
              {available
                ? "Available for new requests"
                : "Not accepting new requests"}
            </p>
          </div>
          <Button
            variant="outline"
            disabled={session.busy}
            onClick={() =>
              void session.perform(async () => {
                const value = await setStaffAvailability(!available);
                setAvailable(value.available);
              })
            }
          >
            {available ? "Pause availability" : "Become available"}
          </Button>
        </section>
        {!consultations.length ? (
          <Empty title="No active consultations.">
            New assigned plans will appear here.
          </Empty>
        ) : (
          <div className="panel-list">
            {consultations.map((item) => (
              <button
                className="panel text-link"
                key={item.ownerUid}
                onClick={() =>
                  void session.perform(async () =>
                    setPlan(await loadPlan(item.ownerUid)),
                  )
                }
              >
                Open {item.ownerName || "shared plan"}
              </button>
            ))}
          </div>
        )}
        {plan && <SafetyPlanEditor plan={plan} onChange={setPlan}
          onCompleted={() => setConsultations((current) => current.filter((item) => item.ownerUid !== plan.ownerUid))} />}
        <OrganizationSupportInbox />
        <Button
          variant="quiet"
          onClick={() => void session.perform(session.signOut)}
        >
          Sign out
        </Button>
      </main>
    </div>
  );
}
