// Contains the staff portal code for this part of Olive.
import { useEffect, useState } from "react";
import { LogOut } from "lucide-react";
import { Button, Empty, Logo } from "../../components";
import { useSession } from "../../session";
import {
  loadPlan,
  setStaffAvailability,
  staffConsultations,
  type SafetyPlan,
} from "../../services/safety-plans";
import SafetyPlanEditor from "./SafetyPlanEditor";

export default function StaffPortal() {
  const session = useSession();
  const [consultations, setConsultations] = useState<
    { ownerUid: string; ownerName: string; updatedAt: number }[]
  >([]);
  const [available, setAvailable] = useState(true);
  const [plan, setPlan] = useState<SafetyPlan | null>(null);

  useEffect(() => {
    void session.perform(async () => {
      const value = await staffConsultations();
      setConsultations(value.plans);
      setAvailable(value.available);
    });
  }, []);

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
            <p>{available ? "Available for new requests" : "Not accepting new requests"}</p>
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
                  void session.perform(async () => setPlan(await loadPlan(item.ownerUid)))
                }
              >
                Open {item.ownerName || "shared plan"}
              </button>
            ))}
          </div>
        )}
        {plan && <SafetyPlanEditor plan={plan} onChange={setPlan} />}
        <Button variant="quiet" onClick={() => void session.perform(session.signOut)}>
          Sign out
        </Button>
      </main>
    </div>
  );
}
