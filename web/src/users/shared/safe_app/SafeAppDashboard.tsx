import {
  ArrowRight, FileLock2, HeartHandshake, MapPinned, MessageCircle, NotebookPen,
  PhoneCall, ShieldCheck, Users,
} from "lucide-react";
import type { Page } from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { Button } from "../../../components/SharedComponents";

type Shortcut = {
  page: Page;
  label: string;
  description: string;
  icon: typeof NotebookPen;
};

const tools: Shortcut[] = [
  { page: "Notes", label: "Notes & incidents", description: "Write a private note or record an incident.", icon: NotebookPen },
  { page: "Documents", label: "Documents", description: "Open your files and documents shared with you.", icon: FileLock2 },
  { page: "Messages", label: "Friends & messages", description: "Pick up a conversation or find a friend.", icon: MessageCircle },
  { page: "Location", label: "Location", description: "See your location options.", icon: MapPinned },
];

const contactConnections: Shortcut[] = [
  { page: "Messages", label: "Friends & messages", description: "Check in with people you support.", icon: MessageCircle },
  { page: "Documents", label: "Documents", description: "Find shared documents and your own files.", icon: FileLock2 },
];
const contactTools = tools.filter(({ page }) => page === "Notes" || page === "Location");

const support: Shortcut[] = [
  { page: "Contacts", label: "Trusted contacts", description: "Manage the people in your support network.", icon: Users },
  { page: "StaffHelp", label: "Staff help", description: "Start a support conversation.", icon: HeartHandshake },
  { page: "Hotlines", label: "Hotlines", description: "Find a helpline you can call.", icon: PhoneCall },
];

function ShortcutSection({ title, items, onOpen }: {
  title: string;
  items: Shortcut[];
  onOpen: (page: Page) => void;
}) {
  return (
    <section className="safe-dashboard-section">
      <h2>{title}</h2>
      <div className="safe-shortcuts">
        {items.map(({ page, label, description, icon: Icon }) => (
          <button className="safe-task" key={page} type="button"
            aria-labelledby={'task-label-' + page} aria-describedby={'task-description-' + page}
            onClick={() => onOpen(page)}>
            <span className="safe-task-icon"><Icon size={21} aria-hidden="true" /></span>
            <span className="safe-task-copy">
              <strong id={'task-label-' + page}>{label}</strong>
              <span id={'task-description-' + page}>{description}</span>
            </span>
            <ArrowRight className="safe-task-arrow" size={18} aria-hidden="true" />
          </button>
        ))}
      </div>
    </section>
  );
}

export default function Home() {
  const session = useSession();
  const isUser = session.profile?.role === "USER";
  return (
    <div className="safe-dashboard">
      <section className="safe-plan-feature" aria-labelledby="home-plan-heading">
        <div className="safe-plan-feature-copy">
          <h2 id="home-plan-heading">{isUser ? "Your safety plan" : "Shared safety plans"}</h2>
          <p>{isUser
            ? "Keep your next steps, important documents, and backup plan in one place."
            : "Review the plans people have shared with you so you know how to help."}</p>
          <Button onClick={() => session.setPage("SafetyPlan")}>
            {isUser ? "Open safety plan" : "View shared plans"} <ArrowRight size={17} aria-hidden="true" />
          </Button>
        </div>
        <div className="safe-plan-feature-mark" aria-hidden="true"><ShieldCheck strokeWidth={1.25} /></div>
      </section>
      {isUser ? (
        <>
          <ShortcutSection title="Your tools" items={tools} onOpen={session.setPage} />
          <ShortcutSection title="Find support" items={support} onOpen={session.setPage} />
        </>
      ) : (
        <>
          <ShortcutSection title="Stay connected" items={contactConnections} onOpen={session.setPage} />
          <ShortcutSection title="Personal tools" items={contactTools} onOpen={session.setPage} />
        </>
      )}
    </div>
  );
}
