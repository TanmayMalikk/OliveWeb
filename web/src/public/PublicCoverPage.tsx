// The public cover owns no private session; private entry opens a fresh document.
import { useRef, useState } from "react";
import { Leaf } from "lucide-react";
import Game from "./covers/OliveGamesCover";

export default function PublicCoverPage({ onPrivateEntry }: { onPrivateEntry: () => void }) {
  const [accountOpen, setAccountOpen] = useState(false);
  const lastOliveTap = useRef(0);
  const tapOlive = () => {
    const now = Date.now();
    if (now - lastOliveTap.current > 1500) {
      lastOliveTap.current = now;
      return;
    }
    lastOliveTap.current = 0;
    onPrivateEntry();
  };
  return (
    <main
      id="main"
      className="cover-container game-cover"
    >
      <nav
        className="cover-account-tabs"
        role="tablist"
        aria-label="Little Grove pages"
      >
        <button
          type="button"
          role="tab"
          aria-selected={!accountOpen}
          aria-controls="cover-display-panel"
          onClick={() => setAccountOpen(false)}
        >
          Game
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={accountOpen}
          aria-controls="cover-account-panel"
          onClick={() => setAccountOpen(true)}
        >
          Account
        </button>
      </nav>
      <div
        id="cover-display-panel"
        className="cover-tab-panel"
        role="tabpanel"
        hidden={accountOpen}
      >
        <Game />
      </div>
      <section
        id="cover-account-panel"
        className="cover-account-panel"
        role="tabpanel"
        aria-label="Account"
        hidden={!accountOpen}
      >
        <span className="eyebrow">ACCOUNT</span>
        <h1>Your account.</h1>
        <p>Account access and private settings.</p>
        <button
          type="button"
          className="cover-hidden-olive"
          aria-label="olive icon"
          onClick={tapOlive}
        >
          <Leaf aria-hidden="true" />
        </button>
      </section>
    </main>
  );
}
