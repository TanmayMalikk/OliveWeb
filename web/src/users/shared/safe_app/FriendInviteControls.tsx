import { useEffect, useState } from "react";
import { Button, Field, Form, Modal } from "../../../components/SharedComponents";
import { accounts, cloudMode } from "../../../services/AccountService";
import { claimFriendInvite, createFriendInvite, subscribeFriendInvite, type FriendInvite } from "../../../services/FriendInviteService";

export function InviteCodeEntry() {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState(false);
  return <Form onSubmit={async () => {
    setBusy(true); setFeedback(""); setError(false);
    try {
      const result = await claimFriendInvite(code);
      setCode(""); setFeedback(`Connected with ${result.username}. You can add another code.`);
    } catch (failure) {
      setError(true); setFeedback(failure instanceof Error ? failure.message : "Unable to use this invite. Try again.");
    } finally { setBusy(false); }
  }}>
    <Field label="Have an invite code? (optional)" name="inviteCode" value={code}
      onChange={(event: React.ChangeEvent<HTMLInputElement>) => setCode(event.target.value)}
      maxLength={32} autoComplete="off" autoCapitalize="none" autoCorrect="off" spellCheck={false} disabled={busy || !cloudMode} />
    <Button type="submit" variant="outline" disabled={!cloudMode || busy || !code.trim()}>
      {busy ? "Connecting…" : "Add connection"}
    </Button>
    {feedback && <p role={error ? "alert" : "status"}>{feedback}</p>}
  </Form>;
}

export function FriendInviteControls() {
  const uid = accounts.identity?.uid;
  const [opened, setOpened] = useState(false);
  const [invite, setInvite] = useState<FriendInvite>();
  const [unavailable, setUnavailable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");
  useEffect(() => {
    setOpened(false); setInvite(undefined); setUnavailable(false); setFeedback("");
  }, [uid]);
  async function generate(refresh: boolean) {
    setOpened(true); setBusy(true); setFeedback(""); setInvite(undefined); setUnavailable(false);
    try { setInvite(await createFriendInvite(refresh)); setUnavailable(false); }
    catch (failure) { setFeedback(failure instanceof Error ? failure.message : "Unable to create an invite."); }
    finally { setBusy(false); }
  }
  useEffect(() => {
    if (!opened || !uid) return;
    let active = true;
    let stop: (() => void) | undefined;
    void subscribeFriendInvite(uid, value => {
      if (active && invite && (value.code !== invite.code || value.claimedBy)) setUnavailable(true);
    }).then(cleanup => { if (active) stop = cleanup; else cleanup(); }).catch(() => undefined);
    return () => { active = false; stop?.(); };
  }, [opened, uid, invite?.code]);
  useEffect(() => {
    if (!invite) return;
    const timer = setTimeout(() => setUnavailable(true), Math.max(0, invite.expiresAt - Date.now()));
    return () => clearTimeout(timer);
  }, [invite]);
  async function copy(value: string) {
    try { await navigator.clipboard.writeText(value); setFeedback("Copied."); }
    catch { setFeedback("Select and copy the code or link shown above."); }
  }
  return <>
    <Button disabled={!cloudMode || busy} onClick={() => void generate(false)}>Invite</Button>
    <InviteCodeEntry />
    {opened && <Modal title="Invite someone" onClose={() => setOpened(false)}>
      {busy && <p role="status">Preparing invite…</p>}
      {unavailable && <p>This invite has expired or been used. Refresh to create another.</p>}
      {invite && !unavailable && <>
        <p><strong>{invite.code}</strong></p>
        <p style={{ overflowWrap: "anywhere" }}>{invite.link}</p>
        <p>Expires {new Date(invite.expiresAt).toLocaleString()}. One use within 48 hours.</p>
        <Button variant="outline" onClick={() => void copy(invite.code)}>Copy code</Button>
        <Button variant="outline" onClick={() => void copy(invite.link)}>Copy link</Button>
      </>}
      <p>Refreshing cancels the previous code.</p>
      <Button disabled={busy} onClick={() => void generate(true)}>Refresh invite code</Button>
      {feedback && <p role="status">{feedback}</p>}
    </Modal>}
  </>;
}
