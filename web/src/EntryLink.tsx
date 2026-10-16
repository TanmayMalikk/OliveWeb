import { useState } from "react";
import { Copy, Check, ArrowRight } from "lucide-react";
import { Button, Logo, Branch } from "./components";
import { useSession } from "./session";
export default function EntryLink() {
  const session = useSession();
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  return (
    <main id="main" className="link-page">
      <Logo />
      <div className="panel personal-link-panel">
        <Branch />
        <span className="eyebrow">KEEP THIS SOMEWHERE YOU TRUST</span>
        <h1>Your own doorway.</h1>
        <p>
          This link opens your account’s entry screen. You’ll still need to sign
          in or enter your browser PIN. The plain website opens Little Grove.
        </p>
        <label className="field">
          <span>Your personal link</span>
          <input
            value={session.personalLink}
            readOnly
            onFocus={(e) => e.target.select()}
            aria-label="Your personal entry link"
          />
        </label>
        <div className="link-actions">
          <Button
            variant="outline"
            onClick={() =>
              void navigator.clipboard
                .writeText(session.personalLink)
                .then(() => setCopied(true))
                .catch(() => setCopyError(true))
            }
          >
            {copied ? <Check size={17} /> : <Copy size={17} />}{" "}
            {copied ? "Copied" : "Copy link"}
          </Button>
          <a className="button" href={session.personalLink}>
            Open my personal link <ArrowRight size={17} />
          </a>
        </div>
        {copyError && <p role="status">Select and copy the link above.</p>}
        <p className="help-note">
          Treat this link as private. Creating a replacement link invalidates
          the previous one. Browser history, bookmarks, and anyone with access
          to your device may still reveal that you visited the site.
        </p>
        <Button variant="quiet" onClick={session.quickExit}>
          Continue to my cover <ArrowRight size={17} />
        </Button>
      </div>
    </main>
  );
}
