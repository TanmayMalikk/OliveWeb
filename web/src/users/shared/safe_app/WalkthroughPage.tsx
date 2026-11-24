// All safe-app roles: two-view Table of Contents hub and focused interactive feature walkthrough.
import { useState } from "react";
import {
  ArrowLeft,
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
  ShieldCheck,
} from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button } from "../../../components/SharedComponents";
import {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
  walkthroughTopicGroups,
  walkthroughTopicsForRole,
  type WebWalkthroughTopic,
} from "./WalkthroughCatalog";
import { WalkthroughInteractiveDemo } from "./WalkthroughDemos";

export {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
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

export default function WalkthroughPage() {
  const session = useSession();
  const topics = walkthroughTopicsForRole(session.profile?.role);
  const groups = walkthroughTopicGroups(topics);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [completedDemos, setCompletedDemos] = useState<Record<string, boolean>>({});

  const activeIndex = selectedId
    ? topics.findIndex((topic) => topic.id === selectedId)
    : -1;
  const activeTopic = activeIndex >= 0 ? topics[activeIndex] : null;
  const exploredCount = topics.filter((topic) => completedDemos[topic.id]).length;

  const markDemoInteracted = (topicId: string) => {
    setCompletedDemos((prev) => (prev[topicId] ? prev : { ...prev, [topicId]: true }));
  };

  if (!activeTopic) {
    let displayIndex = 0;
    return (
      <div className="safe-dashboard" data-testid="walkthrough-page">
        <section className="panel" aria-labelledby="walkthrough-toc-heading">
          <h2 id="walkthrough-toc-heading">Table of contents</h2>
          <p>
            Pick any Olive Branch feature below to try its interactive practice sandbox, read how it works,
            or jump straight to the live tool.
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
            <span className="tag" data-testid="walkthrough-progress-count">
              {exploredCount} of {topics.length} explored
            </span>
            <Button
              data-testid="walkthrough-start-tour"
              onClick={() => topics[0] && setSelectedId(topics[0].id)}
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
                  const isExplored = Boolean(completedDemos[topic.id]);
                  return (
                    <button
                      key={topic.id}
                      type="button"
                      className="safe-task"
                      data-testid={`walkthrough-toc-${topic.id}`}
                      onClick={() => setSelectedId(topic.id)}
                    >
                      <span className="safe-task-icon" aria-hidden="true">
                        {isExplored ? <CheckCircle2 size={21} /> : <TopicIcon size={21} />}
                      </span>
                      <span className="safe-task-copy">
                        <strong>
                          {displayIndex}. {topic.title}{" "}
                          {isExplored && <span className="tag">Explored</span>}
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

  const ActiveIcon = TOPIC_ICONS[activeTopic.id] ?? BookOpen;
  const demoDone = Boolean(completedDemos[activeTopic.id]);

  return (
    <div className="safe-dashboard" data-testid="walkthrough-page">
      <section
        className="panel"
        aria-labelledby="walkthrough-active-heading"
        data-testid="walkthrough-active-feature"
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            gap: "0.75rem",
            flexWrap: "wrap",
            marginBottom: "0.75rem",
          }}
        >
          <Button
            variant="quiet"
            data-testid="walkthrough-back-to-toc"
            onClick={() => setSelectedId(null)}
          >
            <ArrowLeft size={16} aria-hidden="true" /> Table of contents
          </Button>
          <span className="tag">
            Feature {activeIndex + 1} of {topics.length}
          </span>
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
          <span className="safe-task-icon" aria-hidden="true">
            <ActiveIcon size={22} />
          </span>
          <div>
            <h2 id="walkthrough-active-heading" style={{ margin: 0 }}>
              {activeTopic.title}
            </h2>
            <p style={{ margin: "0.25rem 0 0" }}>{activeTopic.summary}</p>
          </div>
        </div>

        <hr style={{ margin: "1rem 0", opacity: 0.25 }} />

        <div className="panel" data-testid="walkthrough-demo-card" style={{ marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>Interactive sandbox</h3>
          <p style={{ marginTop: 0 }}>
            <strong>Practice sandbox · Changes here do not affect your account</strong>
          </p>

          <WalkthroughInteractiveDemo
            topicId={activeTopic.id}
            onPracticeCompleted={() => markDemoInteracted(activeTopic.id)}
          />

          {demoDone && (
            <p role="status" data-testid="walkthrough-demo-feedback" style={{ marginTop: "0.75rem" }}>
              <CheckCircle2 size={16} aria-hidden="true" /> Practice complete! Reset the demo or open{" "}
              {activeTopic.targetLabel} whenever you are ready.
            </p>
          )}
        </div>

        <div className="panel" style={{ marginBottom: "1rem" }}>
          <h3 style={{ marginTop: 0 }}>How it works</h3>
          <ol style={{ margin: 0, paddingLeft: "1.25rem", display: "grid", gap: "0.5rem" }}>
            {activeTopic.steps.map((step, idx) => (
              <li key={idx}>{step}</li>
            ))}
          </ol>
        </div>

        <div style={{ marginBottom: "1rem" }}>
          <Button
            onClick={() => session.setPage(activeTopic.targetPage)}
            data-testid="walkthrough-open-feature"
          >
            Open {activeTopic.targetLabel} <ArrowRight size={17} aria-hidden="true" />
          </Button>
        </div>

        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            gap: "0.75rem",
            flexWrap: "wrap",
          }}
        >
          <Button
            variant="quiet"
            disabled={activeIndex <= 0}
            data-testid="walkthrough-prev-topic"
            onClick={() => {
              const prev = nextWalkthroughTopicIndex(activeIndex, topics.length, -1);
              setSelectedId(topics[prev].id);
            }}
          >
            Previous feature
          </Button>
          <Button
            variant="quiet"
            data-testid="walkthrough-toc-button"
            onClick={() => setSelectedId(null)}
          >
            Table of contents
          </Button>
          <Button
            disabled={activeIndex >= topics.length - 1}
            data-testid="walkthrough-next-topic"
            onClick={() => {
              const next = nextWalkthroughTopicIndex(activeIndex, topics.length, 1);
              setSelectedId(topics[next].id);
            }}
          >
            Next feature
          </Button>
        </div>
      </section>
    </div>
  );
}
