import { ArrowLeft, ChevronRight, ShieldCheck } from "lucide-react";
import { Empty } from "../../components/SharedComponents";
import type { SafetyPlan } from "../../services/SafetyPlanService";
import SafetyPlanEditor from "./StaffSafetyPlanEditor";

type Consultation = { ownerUid: string; updatedAt: number; ticketNumber: number };

export function ConsultationWorkspace({ items, plan, onOpen, onChange, onCompleted, busy }: {
  items: Consultation[];
  plan: SafetyPlan | null;
  onOpen: (ownerUid: string) => void;
  onChange: (plan: SafetyPlan | null) => void;
  onCompleted?: () => void;
  busy: boolean;
}) {
  return (
    <div className="consultation-workspace" data-detail-open={Boolean(plan)}>
      <section className="panel consultation-list" aria-label="Assigned consultations">
        <h2>Assigned consultations</h2>
        {items.length ? items.map(({ ownerUid, ticketNumber, updatedAt }) => (
          <button className="organization-ticket" key={ownerUid} type="button"
            aria-pressed={plan?.ownerUid === ownerUid} disabled={busy} onClick={() => onOpen(ownerUid)}>
            <span className="organization-ticket-icon"><ShieldCheck size={18} aria-hidden="true" /></span>
            <span className="consultation-ticket-copy">
              <strong>{ticketNumber >= 0 ? "Consult Ticket #" + ticketNumber : "Consult Ticket"}</strong>
              {updatedAt > 0 && <small>Updated {new Date(updatedAt).toLocaleDateString()}</small>}
            </span>
            <ChevronRight size={16} aria-hidden="true" />
          </button>
        )) : <p>No active consultations.</p>}
      </section>
      <div className="consultation-detail">
        {plan ? (
          <>
            <button className="text-link workspace-list-back" type="button" onClick={() => onChange(null)}>
              <ArrowLeft size={17} aria-hidden="true" /> Back to consultations
            </button>
            <SafetyPlanEditor key={plan.ownerUid} plan={plan} onChange={onChange} onCompleted={onCompleted} />
          </>
        ) : <Empty title="Select a consultation">Its primary and backup plans will open here.</Empty>}
      </div>
    </div>
  );
}
