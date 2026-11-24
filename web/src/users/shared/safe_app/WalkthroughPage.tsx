// All safe-app roles: Walkthrough Table of Contents launching live guided coachmark tours.
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
  nextWalkthroughTourTopicId,
  walkthroughTopicGroups,
  walkthroughTopicsForRole,
  type WebWalkthroughTopic,
} from "./WalkthroughCatalog";

export {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
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
  onStartTour?: (topicId: string) => void;
}) {
  const session = useSession();
  const topics = walkthroughTopicsForRole(session.profile?.role);
  const groups = walkthroughTopicGroups(topics);

  const launchTopic = (topic: WebWalkthroughTopic) => {
    if (onStartTour) {
      onStartTour(topic.id);
    } else {
      session.setPage(topic.tourPage);
    }
  };

  let displayIndex = 0;

  return (
    <div className="safe-dashboard" data-testid="walkthrough-page">
      <section className="panel" aria-labelledby="walkthrough-toc-heading">
        <h2 id="walkthrough-toc-heading">Table of contents</h2>
        <p>
          Pick any feature below to open the live Olive Branch screen with an interactive popup guide
          pointing right at the controls you use.
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
          <span className="tag">{topics.length} features</span>
          <Button
            data-testid="walkthrough-start-tour"
            onClick={() => topics[0] && launchTopic(topics[0])}
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
                return (
                  <button
                    key={topic.id}
                    type="button"
                    className="safe-task"
                    data-testid={`walkthrough-toc-${topic.id}`}
                    onClick={() => launchTopic(topic)}
                  >
                    <span className="safe-task-icon" aria-hidden="true">
                      <TopicIcon size={21} />
                    </span>
                    <span className="safe-task-copy">
                      <strong>
                        {displayIndex}. {topic.title}
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
