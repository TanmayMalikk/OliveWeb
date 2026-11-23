// Safe-app users and safe contacts: interactive feature walkthrough with table of contents and practice sandbox.
import { useState } from "react";
import {
  ArrowRight,
  BookOpen,
  CheckCircle2,
  FileLock2,
  HeartHandshake,
  History as HistoryIcon,
  Lock,
  LogOut,
  MapPinned,
  MessageCircle,
  NotebookPen,
  PhoneCall,
  Play,
  RotateCcw,
  ShieldCheck,
} from "lucide-react";
import type { Page, Role } from "../../../domain/DomainModels";
import { Button } from "../../../components/SharedComponents";
import { useSession } from "../../../session/SessionProvider";

export interface WebWalkthroughTopic {
  id: string;
  title: string;
  summary: string;
  steps: [string, string, string];
  demoPrompt: string;
  demoResult: string;
  targetPage: Page;
  targetLabel: string;
  icon: typeof BookOpen;
  userOnly?: boolean;
}

const ALL_TOPICS: WebWalkthroughTopic[] = [
  {
    id: "cover_entry",
    title: "Cover games & secret entry",
    summary:
      "Use Little Grove as a public game cover and unlock Your Safe Space via the hidden olive gesture or personal entry link.",
    steps: [
      "Visitors see Little Grove with six playable botanical games (Nature Pairs, Olive Flight, Olive Crossing, Canopy Breaker, Grove 2048, and Grove Tetris).",
      "Open the Account tab on the cover page and double-tap the small olive leaf icon (or open your personal private-entry link).",
      "Sign in with your account credentials or enter your enrolled 4–6 digit browser PIN to unlock Your Safe Space.",
    ],
    demoPrompt:
      "Simulate double-tapping the hidden olive icon in Little Grove's Account tab to open the private entry gate.",
    demoResult:
      "Private entry gate verified! In the real cover, double-tapping the olive opens your PIN or password prompt.",
    targetPage: "Settings",
    targetLabel: "Settings",
    icon: Lock,
    userOnly: true,
  },
  {
    id: "quick_exit",
    title: "Quick Exit & auto-lock",
    summary:
      "Return to Little Grove immediately or let backgrounding and the idle timer lock your session.",
    steps: [
      "Click the floating Quick Exit button (or press Escape) from any private page to clear active keys and return to Little Grove.",
      "Switching browser tabs, minimizing the window, or using the browser Back button locks the private workspace.",
      "In Settings → Privacy, choose your idle-lock timeout (1 to 10 minutes) with a 15-second warning prompt.",
    ],
    demoPrompt:
      "Simulate a Quick Exit check to see how in-memory keys clear and return to the cover without losing saved work.",
    demoResult:
      "Quick Exit simulation complete! Your active session stays untouched inside this practice sandbox.",
    targetPage: "Settings",
    targetLabel: "Settings",
    icon: LogOut,
    userOnly: true,
  },
  {
    id: "safety_plan",
    title: "Safety plan",
    summary:
      "Build primary and backup safety steps, save changes locally and in the cloud, share with trusted contacts, or request staff consultation.",
    steps: [
      "Customize your primary safety plan, backup plan, and emergency checklist sections.",
      "Edits save locally and sync with field-level conflict resolution when connected.",
      "Grant or revoke read access for accepted contacts, or request a safety-plan consultation with organization staff.",
    ],
    demoPrompt:
      "Practice checking off a sample emergency grab-bag item and previewing contact sharing permissions.",
    demoResult:
      "Checklist item marked complete in sandbox! You can open Safety plan anytime to edit your real plan.",
    targetPage: "SafetyPlan",
    targetLabel: "Safety plan",
    icon: ShieldCheck,
  },
  {
    id: "notes_incidents",
    title: "Notes & incident records",
    summary:
      "Write private notes and dated incident reports with map locations, encrypted attachments, pinning, trash, and edit history.",
    steps: [
      "Choose Note for general reminders or Incident to record the date, time, address/map pin, narrative, and encrypted attachments.",
      "Pin important entries to the top, filter by Notes or Incidents, and search by keyword.",
      "Inspect the timestamped edit history log, share an entry with a friend, or restore items from the trash.",
    ],
    demoPrompt:
      "Practice pinning a sample incident record and inspecting its timestamped edit history.",
    demoResult:
      "Sample incident pinned and edit history verified! None of your real notes were modified.",
    targetPage: "Notes",
    targetLabel: "Notes & incidents",
    icon: NotebookPen,
  },
  {
    id: "documents",
    title: "Encrypted documents",
    summary:
      "Upload, preview, download, and share client-side encrypted PDFs, images, video, and audio files.",
    steps: [
      "Upload important files (IDs, legal records, photos, or evidence); each file is encrypted before leaving your device.",
      "Open images, PDFs, audio, and video directly in the built-in encrypted media viewer.",
      "Share selected documents with accepted contacts and revoke access whenever needed.",
    ],
    demoPrompt:
      "Simulate encrypting a sample PDF document with an AES-256 key and preparing it for private storage.",
    demoResult:
      "Sample PDF encrypted in sandbox! Voice messages stay in chat and never clutter Document storage.",
    targetPage: "Documents",
    targetLabel: "Documents",
    icon: FileLock2,
  },
  {
    id: "messaging_voice",
    title: "Friends, messages & voice notes",
    summary:
      "Connect via username search or 48-hour invite codes, and exchange encrypted messages, chat-only voice recordings, and shared records.",
    steps: [
      "Search by username to send a friend request, or create/redeem a 48-hour one-time friend invite code or link.",
      "Send encrypted messages, record chat-only voice messages, and share documents, notes, incidents, or safety plans in chat.",
      "Add private per-contact notes, search conversation history, unsend your messages, or archive chats.",
    ],
    demoPrompt:
      "Practice generating a sample 48-hour friend invite code and previewing an encrypted chat-only voice message.",
    demoResult:
      "Sample invite code (OLIVE-DEMO-48H) and encrypted voice bubble previewed in sandbox!",
    targetPage: "Messages",
    targetLabel: "Friends & messages",
    icon: MessageCircle,
  },
  {
    id: "location",
    title: "Location & sharing",
    summary:
      "Check your current coordinates, view locations shared with you on the interactive map, and manage location sharing.",
    steps: [
      "Open Location to view the interactive map and look up your current coordinates when you choose.",
      "Review live locations that connected users have shared with you.",
      "Use the Android app when you need continuous background live-location sharing and turn-by-turn navigation.",
    ],
    demoPrompt:
      "Simulate a one-time location lookup on the sandbox map without storing your coordinates.",
    demoResult:
      "Sandbox map pin previewed (Edmonton, AB)! Your real account location was not changed.",
    targetPage: "Location",
    targetLabel: "Location",
    icon: MapPinned,
  },
  {
    id: "staff_help",
    title: "Staff help & support chats",
    summary:
      "Start a support conversation with organization staff or escalate an existing conversation into a support ticket.",
    steps: [
      "Open Staff help to request an available organization support staff member.",
      "Once assigned, your support ticket conversation opens in Friends & messages.",
      "Share relevant notes or plans in the ticket and add an accepted friend if you want them included.",
    ],
    demoPrompt:
      "Simulate opening a support ticket request and previewing staff assignment.",
    demoResult:
      "Sample Support Ticket #104 assigned in sandbox! Open Staff help when you want to reach real staff.",
    targetPage: "StaffHelp",
    targetLabel: "Staff help",
    icon: HeartHandshake,
    userOnly: true,
  },
  {
    id: "hotlines_contacts",
    title: "Hotlines & trusted contacts",
    summary:
      "Search Canadian crisis lines and shelters by province or city, and organize your trusted support network.",
    steps: [
      "Open Hotlines to filter Canadian helplines, shelters, legal aid, and counseling by province, territory, or city.",
      "Use your saved city or an explicit one-time browser location check without overwriting your profile address.",
      "Open Trusted contacts to store relationship details and keep track of people in your personal support network.",
    ],
    demoPrompt:
      "Practice filtering sample 24/7 Alberta crisis hotlines and adding a sandbox trusted contact.",
    demoResult:
      "Sample 24/7 crisis line and trusted contact previewed in sandbox!",
    targetPage: "Hotlines",
    targetLabel: "Hotlines",
    icon: PhoneCall,
    userOnly: true,
  },
  {
    id: "activity_settings",
    title: "Account activity, passkeys & settings",
    summary:
      "Audit settings and trigger events, rotate your personal entry link or PIN, and enroll a Passkey Account Recovery Key.",
    steps: [
      "Open Account activity to review and filter timestamped settings changes, trigger events, and failed PIN attempts.",
      "In My profile and Settings, update your profile details, email communication consent, password, trigger phrase, or browser PIN.",
      "Enroll a Passkey Account Recovery Key so your encrypted documents and chat keys can be recovered in a new browser or device.",
    ],
    demoPrompt:
      "Preview a sample security audit log entry and passkey recovery key status.",
    demoResult:
      "Sandbox audit log and passkey recovery status verified!",
    targetPage: "Activity",
    targetLabel: "Account activity",
    icon: HistoryIcon,
  },
];

export function walkthroughTopicsForRole(role: Role | undefined): WebWalkthroughTopic[] {
  if (role === "SAFE_CONTACT") {
    return ALL_TOPICS.filter((topic) => !topic.userOnly);
  }
  return ALL_TOPICS;
}

export function clampWalkthroughStep(stepIndex: number, totalSteps: number): number {
  if (totalSteps <= 1) return 0;
  return Math.max(0, Math.min(totalSteps - 1, stepIndex));
}

export function nextWalkthroughTopicIndex(
  currentIndex: number,
  totalTopics: number,
  delta: number,
): number {
  if (totalTopics <= 1) return 0;
  return Math.max(0, Math.min(totalTopics - 1, currentIndex + delta));
}

export default function WalkthroughPage() {
  const session = useSession();
  const role = session.profile?.role;
  const topics = walkthroughTopicsForRole(role);
  const [selectedId, setSelectedId] = useState<string>(topics[0]?.id ?? "safety_plan");
  const selectedIndex = Math.max(
    0,
    topics.findIndex((topic) => topic.id === selectedId),
  );
  const activeTopic = topics[selectedIndex] ?? topics[0];
  const [stepIndex, setStepIndex] = useState(0);
  const [completedDemos, setCompletedDemos] = useState<Record<string, boolean>>({});

  const currentStep = clampWalkthroughStep(stepIndex, activeTopic.steps.length);
  const demoDone = Boolean(completedDemos[activeTopic.id]);
  const ActiveIcon = activeTopic.icon;

  const selectTopic = (topicId: string) => {
    setSelectedId(topicId);
    setStepIndex(0);
  };

  return (
    <div className="safe-dashboard" data-testid="walkthrough-page">
      <section className="panel" aria-labelledby="walkthrough-intro-heading">
        <h2 id="walkthrough-intro-heading">Interactive feature walkthrough</h2>
        <p>
          Select any feature from the Table of Contents below to read step-by-step instructions,
          try a safe practice demo in the sandbox, or jump straight to the live tool.
        </p>
      </section>

      <section
        className="panel"
        aria-labelledby="walkthrough-active-heading"
        data-testid="walkthrough-active-feature"
      >
        <div
          style={{
            display: "flex",
            alignItems: "flex-start",
            justifyContent: "space-between",
            gap: "1rem",
            flexWrap: "wrap",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
            <span className="safe-task-icon" aria-hidden="true">
              <ActiveIcon size={22} />
            </span>
            <div>
              <span className="tag">
                Feature {selectedIndex + 1} of {topics.length}
              </span>
              <h2 id="walkthrough-active-heading" style={{ marginTop: "0.25rem" }}>
                {activeTopic.title}
              </h2>
              <p style={{ margin: 0 }}>{activeTopic.summary}</p>
            </div>
          </div>
          <Button
            onClick={() => session.setPage(activeTopic.targetPage)}
            data-testid="walkthrough-open-feature"
          >
            Open {activeTopic.targetLabel} <ArrowRight size={17} aria-hidden="true" />
          </Button>
        </div>

        <hr style={{ margin: "1rem 0", opacity: 0.25 }} />

        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: "0.75rem",
            flexWrap: "wrap",
          }}
        >
          <strong role="status" aria-live="polite" data-testid="walkthrough-step-indicator">
            Step {currentStep + 1} of {activeTopic.steps.length}
          </strong>
          <div role="group" aria-label="Walkthrough steps" style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
            {activeTopic.steps.map((_, idx) => (
              <Button
                key={idx}
                variant={idx === currentStep ? "primary" : "outline"}
                aria-pressed={idx === currentStep}
                data-testid={`walkthrough-step-pill-${idx + 1}`}
                onClick={() => setStepIndex(idx)}
              >
                Step {idx + 1}
              </Button>
            ))}
          </div>
        </div>

        <div
          className="panel"
          style={{ marginTop: "0.75rem" }}
          data-testid="walkthrough-step-body"
        >
          <p style={{ margin: 0, fontSize: "1.05rem" }}>{activeTopic.steps[currentStep]}</p>
        </div>

        <div
          style={{
            display: "flex",
            gap: "0.75rem",
            marginTop: "0.75rem",
            flexWrap: "wrap",
          }}
        >
          <Button
            variant="outline"
            disabled={currentStep === 0}
            data-testid="walkthrough-prev-step"
            onClick={() =>
              setStepIndex(clampWalkthroughStep(currentStep - 1, activeTopic.steps.length))
            }
          >
            Previous step
          </Button>
          <Button
            disabled={currentStep >= activeTopic.steps.length - 1}
            data-testid="walkthrough-next-step"
            onClick={() =>
              setStepIndex(clampWalkthroughStep(currentStep + 1, activeTopic.steps.length))
            }
          >
            Next step
          </Button>
        </div>

        <div
          className="panel"
          style={{ marginTop: "1rem" }}
          data-testid="walkthrough-demo-card"
        >
          <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            {demoDone ? (
              <CheckCircle2 size={19} aria-hidden="true" />
            ) : (
              <Play size={19} aria-hidden="true" />
            )}
            <h3 style={{ margin: 0 }}>Interactive practice demo</h3>
          </div>
          <p style={{ margin: "0.35rem 0" }}>
            <small>Practice sandbox · Actions here do not modify your real account</small>
          </p>
          <p style={{ margin: "0.5rem 0" }}>{activeTopic.demoPrompt}</p>
          {demoDone && (
            <div
              className="status-banner status-success"
              role="status"
              data-testid="walkthrough-demo-feedback"
            >
              <span>{activeTopic.demoResult}</span>
            </div>
          )}
          <div style={{ display: "flex", gap: "0.75rem", marginTop: "0.75rem", flexWrap: "wrap" }}>
            <Button
              disabled={demoDone}
              data-testid="walkthrough-demo-action"
              onClick={() =>
                setCompletedDemos((prev) => ({ ...prev, [activeTopic.id]: true }))
              }
            >
              <Play size={16} aria-hidden="true" /> Try practice action
            </Button>
            {demoDone && (
              <Button
                variant="outline"
                data-testid="walkthrough-demo-reset"
                onClick={() =>
                  setCompletedDemos((prev) => ({ ...prev, [activeTopic.id]: false }))
                }
              >
                <RotateCcw size={16} aria-hidden="true" /> Reset demo
              </Button>
            )}
          </div>
        </div>

        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            gap: "0.75rem",
            marginTop: "1rem",
            flexWrap: "wrap",
          }}
        >
          <Button
            variant="quiet"
            disabled={selectedIndex <= 0}
            data-testid="walkthrough-prev-topic"
            onClick={() => {
              const prev = nextWalkthroughTopicIndex(selectedIndex, topics.length, -1);
              selectTopic(topics[prev].id);
            }}
          >
            Previous feature
          </Button>
          <Button
            variant="quiet"
            disabled={selectedIndex >= topics.length - 1}
            data-testid="walkthrough-next-topic"
            onClick={() => {
              const next = nextWalkthroughTopicIndex(selectedIndex, topics.length, 1);
              selectTopic(topics[next].id);
            }}
          >
            Next feature
          </Button>
        </div>
      </section>

      <section
        className="safe-dashboard-section"
        aria-labelledby="walkthrough-toc-heading"
        data-testid="walkthrough-toc"
      >
        <h2 id="walkthrough-toc-heading">Table of contents ({topics.length} features)</h2>
        <div className="safe-shortcuts">
          {topics.map((topic, index) => {
            const TopicIcon = topic.icon;
            const isSelected = topic.id === activeTopic.id;
            const isPracticed = Boolean(completedDemos[topic.id]);
            return (
              <button
                key={topic.id}
                type="button"
                className="safe-task"
                aria-current={isSelected ? "true" : undefined}
                data-testid={`walkthrough-toc-${topic.id}`}
                onClick={() => selectTopic(topic.id)}
              >
                <span className="safe-task-icon" aria-hidden="true">
                  {isPracticed ? <CheckCircle2 size={21} /> : <TopicIcon size={21} />}
                </span>
                <span className="safe-task-copy">
                  <strong>
                    {index + 1}. {topic.title}{" "}
                    {isSelected && <span className="tag">Selected</span>}
                  </strong>
                  <span>{topic.summary}</span>
                </span>
                <ArrowRight className="safe-task-arrow" size={18} aria-hidden="true" />
              </button>
            );
          })}
        </div>
      </section>
    </div>
  );
}
