// All safe-app roles: Walkthrough Table of Contents launching live multi-step coachmark tours.
import {
  ArrowRight,
  BookOpen,
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
  ShieldCheck,
} from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button } from "../../../components/SharedComponents";
import {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
  nextWalkthroughTourStepId,
  nextWalkthroughTourTopicId,
  walkthroughStepCountForTopic,
  walkthroughTopicGroups,
  walkthroughTopicsForRole,
  walkthroughTourStepsForRole,
  type WebWalkthroughTopic,
} from "./WalkthroughCatalog";

export {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
  nextWalkthroughTourStepId,
  nextWalkthroughTourTopicId,
  walkthroughTopicGroups,
  walkthroughTopicsForRole,
  type WebWalkthroughTopic,
};

const TOPIC_ICONS: Record<string, typeof ShieldCheck> = {
  cover_entry: Lock,
  quick_exit: LogOut,
  safety_plan: ShieldCheck,
  notes_incidents: NotebookPen,
  documents: FileLock2,
  messaging_voice: MessageCircle,
  location: MapPinned,
  staff_help: HeartHandshake,
  hotlines_contacts: PhoneCall,
  activity_settings: HistoryIcon,
};

export default function WalkthroughPage({
  onStartTour,
}: {
  onStartTour?: (targetId: string) => void;
}) {
  const session = useSession();
  const role = session.profile?.role;
  const topics = walkthroughTopicsForRole(role);
  const steps = walkthroughTourStepsForRole(role);
  const groups = walkthroughTopicGroups(topics);

  const launchTarget = (targetId: string, fallbackPage: WebWalkthroughTopic["tourPage"]) => {
    if (onStartTour) {
      onStartTour(targetId);
    } else {
      session.setPage(fallbackPage);
    }
  };

  let displayIndex = 0;

  return (
    <div className="safe-dashboard" data-testid="walkthrough-page">
      <section className="panel" aria-labelledby="walkthrough-toc-heading">
        <h2 id="walkthrough-toc-heading">Table of contents</h2>
        <p>
          Pick any feature below to open the live Olive Branch screens and step through every tool
          with floating guided popups.
        </p>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: "0.75rem",
            flexWrap: "wrap",
            marginTop: "0.75rem",
          }}
        >
          <span className="tag">{steps.length} guided steps</span>
          <Button
            data-testid="walkthrough-start-tour"
            onClick={() => steps[0] && launchTarget(steps[0].id, steps[0].tourPage)}
          >
            <Play size={16} aria-hidden="true" /> Start full walkthrough
          </Button>
        </div>
      </section>

      <section
        className="safe-dashboard-section"
        aria-label="Walkthrough table of contents"
        data-testid="walkthrough-toc"
      >
        {groups.map((group) => (
          <div key={group.category.id} style={{ marginBottom: "1.5rem" }}>
            <h3>{group.category.label}</h3>
            <div className="safe-shortcuts">
              {group.topics.map((topic) => {
                displayIndex += 1;
                const TopicIcon = TOPIC_ICONS[topic.id] ?? BookOpen;
                const stepCount = walkthroughStepCountForTopic(role, topic.id);
                return (
                  <button
                    key={topic.id}
                    type="button"
                    className="safe-task"
                    data-testid={`walkthrough-toc-${topic.id}`}
                    onClick={() => launchTarget(topic.id, topic.tourPage)}
                  >
                    <span className="safe-task-icon" aria-hidden="true">
                      <TopicIcon size={21} />
                    </span>
                    <span className="safe-task-copy">
                      <strong>
                        {displayIndex}. {topic.title}{" "}
                        <span className="tag">{stepCount} steps</span>
                      </strong>
                      <span>{topic.summary}</span>
                    </span>
                    <ArrowRight className="safe-task-arrow" size={18} aria-hidden="true" />
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}
