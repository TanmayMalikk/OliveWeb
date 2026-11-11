import { useEffect, useRef, type ReactNode } from "react";
import { ArrowRight, Leaf, LogOut, type LucideIcon } from "lucide-react";
import { useSession } from "../../session/SessionProvider";

export interface WorkspacePage<View extends string> {
  id: View;
  label: string;
  title?: string;
  description: string;
  icon: LucideIcon;
}

export function OrganizationWorkspace<View extends string>({ role, pages, view, onNavigate, children }: {
  role: "Staff" | "Administrator";
  pages: readonly WorkspacePage<View>[];
  view: View;
  onNavigate: (view: View) => void;
  children: ReactNode;
}) {
  const session = useSession();
  const heading = useRef<HTMLHeadingElement>(null);
  const previousView = useRef(view);
  const page = pages.find(({ id }) => id === view);

  useEffect(() => {
    if (previousView.current === view) return;
    window.scrollTo({ top: 0, behavior: "instant" });
    heading.current?.focus({ preventScroll: true });
    previousView.current = view;
  }, [view]);

  return (
    <div className="safe-shell private-shell organization-workspace">
      <aside className="organization-rail">
        <div className="organization-brand">
          <span className="safe-brand-mark"><Leaf size={21} aria-hidden="true" /></span>
          <div><strong>Olive Branch</strong><small>{role} workspace</small></div>
        </div>
        <nav className="safe-nav-group" aria-label={role + " workspace"}>
          {pages.map(({ id, label, icon: Icon }) => (
            <button key={id} type="button" aria-current={view === id ? "page" : undefined}
              onClick={() => onNavigate(id)}>
              <Icon size={18} aria-hidden="true" /> {label}
            </button>
          ))}
        </nav>
        <div className="organization-session">
          <span>@{session.profile?.username}</span>
          <button type="button" onClick={() => void session.perform(session.signOut)}>
            <LogOut size={17} aria-hidden="true" /> Sign out
          </button>
        </div>
      </aside>
      <div className="safe-main organization-main">
        <header className="organization-header">
          <h1 tabIndex={-1} ref={heading}>{page?.title ?? page?.label}</h1>
          <p>{page?.description}</p>
        </header>
        <main id="main" tabIndex={-1}>
          {session.message && <p role="alert" className="form-error">{session.message}</p>}
          {children}
        </main>
      </div>
    </div>
  );
}

export function WorkspaceShortcuts<View extends string>({ pages, onNavigate }: {
  pages: readonly WorkspacePage<View>[];
  onNavigate: (view: View) => void;
}) {
  return (
    <div className="safe-shortcuts">
      {pages.map(({ id, label, description, icon: Icon }) => (
        <button className="safe-task" type="button" key={id} onClick={() => onNavigate(id)}>
          <span className="safe-task-icon"><Icon size={22} aria-hidden="true" /></span>
          <span className="safe-task-copy"><strong>{label}</strong><span>{description}</span></span>
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      ))}
    </div>
  );
}
