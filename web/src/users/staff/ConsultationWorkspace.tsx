import { useState } from "react";
import { ArrowLeft, ChevronDown, ChevronRight, ChevronUp, ShieldCheck } from "lucide-react";
import { Empty } from "../../components/SharedComponents";
import type { Role } from "../../domain/DomainModels";
import type { SafetyPlan } from "../../services/SafetyPlanService";
import SafetyPlanEditor from "./StaffSafetyPlanEditor";

type Consultation = {
  ownerUid: string;
  updatedAt: number;
  ticketNumber: number;
  staffUid?: string;
  staffName?: string;
};

function ConsultationListGroup({
  title,
  items,
  selectedOwnerUid,
  busy,
  expanded,
  onToggle,
  showStaffName,
  onOpen,
}: {
  title: string;
  items: Consultation[];
  selectedOwnerUid?: string;
  busy: boolean;
  expanded: boolean;
  onToggle: () => void;
  showStaffName: boolean;
  onOpen: (ownerUid: string) => void;
}) {
  return (
    <div className="consultation-group">
      <button
        type="button"
        className="consultation-group-toggle"
        aria-expanded={expanded}
        onClick={onToggle}
      >
        <span>
          {title} ({items.length})
        </span>
        {expanded ? <ChevronUp size={16} aria-hidden="true" /> : <ChevronDown size={16} aria-hidden="true" />}
      </button>
      {expanded &&
        (items.length > 0 ? (
          items.map(({ ownerUid, ticketNumber, updatedAt, staffName }) => (
            <button
              className="organization-ticket"
              key={ownerUid}
              type="button"
              aria-pressed={selectedOwnerUid === ownerUid}
              disabled={busy}
              onClick={() => onOpen(ownerUid)}
            >
              <span className="organization-ticket-icon">
                <ShieldCheck size={18} aria-hidden="true" />
              </span>
              <span className="consultation-ticket-copy">
                <strong>
                  {ticketNumber >= 0 ? "Consult Ticket #" + ticketNumber : "Consult Ticket"}
                </strong>
                {showStaffName && staffName && <small>Assigned: {staffName}</small>}
                {updatedAt > 0 && (
                  <small>Updated {new Date(updatedAt).toLocaleDateString()}</small>
                )}
              </span>
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          ))
        ) : (
          <p className="consultation-empty">No plans in this category.</p>
        ))}
    </div>
  );
}

export function ConsultationWorkspace({
  items,
  plan,
  onOpen,
  onChange,
  onCompleted,
  busy,
  role = "STAFF",
}: {
  items: Consultation[];
  plan: SafetyPlan | null;
  onOpen: (ownerUid: string) => void;
  onChange: (plan: SafetyPlan | null) => void;
  onCompleted?: () => void;
  busy: boolean;
  role?: Extract<Role, "STAFF" | "ADMIN">;
}) {
  const [assignedOpen, setAssignedOpen] = useState(true);
  const [waitingOpen, setWaitingOpen] = useState(true);

  const isAdmin = role === "ADMIN";
  const assigned = items.filter((item) => Boolean(item.staffUid));
  const waiting = items.filter((item) => !item.staffUid);

  return (
    <div className="consultation-workspace" data-detail-open={Boolean(plan)}>
      <section className="panel consultation-list" aria-label="Assigned consultations">
        <h2>{isAdmin ? "Safety consultations" : "Assigned consultations"}</h2>
        {items.length === 0 ? (
          <p>No active consultations.</p>
        ) : isAdmin ? (
          <>
            <ConsultationListGroup
              title="Assigned to staff"
              items={assigned}
              selectedOwnerUid={plan?.ownerUid}
              busy={busy}
              expanded={assignedOpen}
              onToggle={() => setAssignedOpen((value) => !value)}
              showStaffName
              onOpen={onOpen}
            />
            <ConsultationListGroup
              title="Waiting for staff"
              items={waiting}
              selectedOwnerUid={plan?.ownerUid}
              busy={busy}
              expanded={waitingOpen}
              onToggle={() => setWaitingOpen((value) => !value)}
              showStaffName={false}
              onOpen={onOpen}
            />
          </>
        ) : (
          <ConsultationListGroup
            title="Assigned to you"
            items={items}
            selectedOwnerUid={plan?.ownerUid}
            busy={busy}
            expanded={assignedOpen}
            onToggle={() => setAssignedOpen((value) => !value)}
            showStaffName={false}
            onOpen={onOpen}
          />
        )}
      </section>
      <div className="consultation-detail">
        {plan ? (
          <>
            <button
              className="text-link workspace-list-back"
              type="button"
              onClick={() => onChange(null)}
            >
              <ArrowLeft size={17} aria-hidden="true" /> Back to consultations
            </button>
            <SafetyPlanEditor
              key={plan.ownerUid}
              plan={plan}
              onChange={onChange}
              onCompleted={onCompleted}
            />
          </>
        ) : (
          <Empty title="Select a consultation">
            Its primary and backup plans will open here.
          </Empty>
        )}
      </div>
    </div>
  );
}
