// All safe-app roles: shows the main safe-app actions.
import { ArrowRight, MessageCircle, NotebookPen, ShieldCheck, Users } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Branch, Button, Forward } from "../../../components/SharedComponents";
import type { Page } from "../../../domain/DomainModels";
export default function Home() {
  const s = useSession();
  return (
    <>
      <section className="safe-welcome">
        <div>
          <span className="eyebrow">THERE’S NO PERFECT PLACE TO START</span>
          <h2>
            One thought.
            <br />
            <em>One small step.</em>
          </h2>
          <p>
            This space is yours. Write something down, reach out to someone you
            trust, or simply take a moment.
          </p>
          <Button onClick={() => s.setPage("Notes")}>
            <Forward>Put a thought into words</Forward>
          </Button>
        </div>
        <Branch />
      </section>
      <div className="safe-card-grid">
        {[
          {
            title: "Your thoughts, kept together.",
            copy: "Notes and incident records, in your own words.",
            page: "Notes" as Page,
            Icon: NotebookPen,
          },
          {
            title: "A familiar voice.",
            copy: "Connect with the people you choose to trust.",
            page: "Messages" as Page,
            Icon: MessageCircle,
          },
          {
            title: "Your people, close by.",
            copy: "Keep trusted contact details in one place.",
            page: "Contacts" as Page,
            Icon: Users,
          },
        ].map(({ title, copy, page, Icon }) => (
          <button
            className="safe-shortcut panel"
            key={page}
            onClick={() => s.setPage(page)}
          >
            <Icon size={24} strokeWidth={1.3} />
            <h3>{title}</h3>
            <p>{copy}</p>
            <ArrowRight size={20} />
          </button>
        ))}
      </div>
      <section className="privacy-note">
        <ShieldCheck size={22} />
        <div>
          <h3>A quick way back.</h3>
          <p>
            Quick Exit or Escape returns to your cover. Switching tabs locks
            this space. A website cannot prevent screenshots or erase browser
            history.
          </p>
        </div>
      </section>
    </>
  );
}

