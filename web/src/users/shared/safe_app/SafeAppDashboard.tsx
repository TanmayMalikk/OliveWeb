import { FileLock2, History, MapPinned, MessageCircle, NotebookPen, ShieldCheck, UserRound } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button } from "../../../components/SharedComponents";
import type { Page } from "../../../domain/DomainModels";

const features = [
  { page: "Notes", label: "Notes & incidents", icon: NotebookPen },
  { page: "Messages", label: "Friends & messages", icon: MessageCircle },
  { page: "SafetyPlan", label: "Safety plan", icon: ShieldCheck },
  { page: "Documents", label: "Documents", icon: FileLock2 },
  { page: "Location", label: "Location", icon: MapPinned },
] as const satisfies readonly { page: Page; label: string; icon: typeof NotebookPen }[];

export default function Home() {
  const session = useSession();
  return (
    <div className="safe-dashboard">
      {session.profile?.role === "USER" && <p>Use Quick Exit to return to Little Grove.</p>}
      <div className="safe-dashboard-account">
        <Button onClick={() => session.setPage("Profile")}><UserRound size={19} /> My profile</Button>
        <Button onClick={() => session.setPage("Activity")}><History size={19} /> Account activity</Button>
      </div>
      <section aria-labelledby="private-features">
        <h2 id="private-features">Private features</h2>
        <div className="safe-feature-grid">
          {features.map(({ page, label, icon: Icon }) => (
            <button className="safe-feature-tile" key={page} type="button" onClick={() => session.setPage(page)}>
              <Icon size={27} aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}
