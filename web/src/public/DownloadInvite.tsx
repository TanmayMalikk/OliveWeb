import { useState } from "react";
import { Button, Modal } from "../components/SharedComponents";

export function DownloadInvite() {
  const [code] = useState(() => {
    const value = new URLSearchParams(location.search).get("invite") ?? "";
    return /^[A-Za-z0-9_-]{8,32}$/.test(value) ? value : "";
  });
  const [opened, setOpened] = useState(Boolean(code));
  const [copied, setCopied] = useState("");
  if (!code) return null;
  return <>
    <p>Your invite code is <strong>{code}</strong>. <Button variant="quiet" onClick={() => setOpened(true)}>View invite</Button></p>
    {opened && <Modal title="You have an invite" onClose={() => setOpened(false)}>
      <p>Enter this code during onboarding to connect with the person who invited you.</p>
      <p><strong>{code}</strong></p>
      <p>Already installed? Open Messages and enter the code there.</p>
      <p>The code works once and expires 48 hours after it was created. A refreshed code replaces it.</p>
      <Button onClick={() => { void navigator.clipboard.writeText(code)
        .then(() => setCopied("Code copied."), () => setCopied("Select the code above to copy it.")); }}>Copy code</Button>
      <p role="status">{copied}</p>
      <a className="button" href="/download/android">Download Android app</a>
    </Modal>}
  </>;
}
