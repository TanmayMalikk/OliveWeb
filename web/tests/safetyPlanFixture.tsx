import { useState } from "react";
import { createRoot } from "react-dom/client";
import { emptyHistory } from "../src/domain/DomainModels";
import type { SessionValue } from "../src/session/SessionContext";
import { SessionContext } from "../src/session/SessionContext";
import type { SafetyPlan as Plan } from "../src/services/SafetyPlanService";
import StaffSafetyPlanEditor from "../src/users/staff/StaffSafetyPlanEditor";
import { loadPlan } from "./safetyPlanBackend";
import OrganizationSupportInbox from "../src/users/shared/OrganizationSupportInbox";
import FriendsAndMessagesPage from "../src/users/shared/safe_app/FriendsAndMessagesPage";
import AdminPortal from "../src/users/admin/AdminDashboardPage";
import StaffPortal from "../src/users/staff/StaffDashboardPage";
import SafetyPlan from "../src/users/shared/safe_app/SafetyPlanPage";
import { DocumentsPage, LocationPage } from "../src/users/shared/safe_app/SupportAndLocationPages";
import UserNotesPage from "../src/users/user/contacts_and_notes/UserNotesPage";
import "../src/styles.css";

const events: unknown[][] = [];
Object.assign(window, { panelEvents: events });
const unused = () => { throw new Error("Unexpected session action"); };
const session: SessionValue = {
  path: "/safety", go: unused, phase: "safe", personalLink: "", hasEntryToken: true,
  profile: null, history: emptyHistory(), enrollment: null, page: "SafetyPlan",
  setPage: page => { events.push(["page", page]); },
  requestedConversation: null, setRequestedConversation: value => { events.push(["ticket", value]); },
  busy: false, message: "", clearMessage: unused, perform: work => work(),
  authenticate: unused, completeSetup: unused, beginEntry: unused, quickExit: unused,
  unlock: unused, saveProfile: unused, saveEntry: unused, record: unused, refresh: unused,
  signOut: unused, idleSeconds: null, idleTimeoutSeconds: 60, setIdleTimeoutSeconds: unused,
  noteInteraction: unused, cloudMode: true, onboardingProfile: null,
};
const panel = new URLSearchParams(location.search).get("panel");
if (panel === "shared-messages") {
  session.history.private_entries.push({ id: "record", timestamp: 1, updatedAt: 1, title: "Private fixture incident",
    body: "Synthetic incident details", kind: "Incident", pinned: false, edits: [],
    incidentDate: "2026-05-15", incidentTime: "13:20", incidentLocation: "Fixture location", attachmentIds: ["doc"] });
}
const initialConsultation = panel === "staff" ? await loadPlan("owner") as Plan : null;
function StaffConsultation() {
  const [plan, setPlan] = useState(initialConsultation);
  const [completed, setCompleted] = useState(false);
  return plan ? <StaffSafetyPlanEditor plan={plan} onChange={setPlan} onCompleted={() => setCompleted(true)} />
    : <p role="status">{completed ? "Consultation complete" : "Consultation no longer available"}</p>;
}
function NotesFixture() {
  const [history, setHistory] = useState(emptyHistory);
  return <SessionContext.Provider value={{ ...session, history, saveEntry: async (entry) => {
    setHistory(previous => ({ ...previous, private_entries: [...previous.private_entries.filter(row => row.id !== entry.id), entry] }));
  } }}><UserNotesPage /></SessionContext.Provider>;
}

const Page = panel === "admin" ? AdminPortal : panel === "staff-office" ? StaffPortal : panel === "messages" || panel === "shared-messages" ? FriendsAndMessagesPage : panel === "inbox" ? OrganizationSupportInbox : panel === "staff" ? StaffConsultation : panel === "documents" ? DocumentsPage : panel === "location" ? LocationPage : panel === "notes" ? NotesFixture : SafetyPlan;
createRoot(document.getElementById("root")!).render(
  <SessionContext.Provider value={session}><Page /></SessionContext.Provider>,
);
