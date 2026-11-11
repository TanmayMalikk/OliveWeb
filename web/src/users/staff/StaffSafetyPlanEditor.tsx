// Staff: lets staff edit a user safety plan during consultation.
import { useEffect, useRef, useState } from "react";
import { Button } from "../../components/SharedComponents";
import { accounts } from "../../services/AccountService";
import { completePlans, observePlan, olderSafetySections, readSafetyChecklist, safetyChecklistText, safetySectionLabels, safetySections, saveField, writeSafetyChecklist, type SafetyPlan } from "../../services/SafetyPlanService";
import { observeBrowserDocuments, openBrowserDocument, type BrowserDocument } from "../../services/MediaService";
import { useSession } from "../../session/SessionProvider";
import { SafeMediaViewer } from "../shared/safe_app/SafeMediaViewer";

interface SafetyPlanEditorProps {
  plan: SafetyPlan;
  onChange: (plan: SafetyPlan | null) => void;
  onCompleted?: () => void;
}

export default function SafetyPlanEditor({ plan, onChange, onCompleted }: SafetyPlanEditorProps) {
  const session = useSession();
  const identity = accounts.identity;
  const [drafts, setDrafts] = useState<Record<string, { key: string; itemId: string; text: string; revision: number; baseValue: string }>>({});
  const lastAttempt = useRef<{ draftKey: string; text: string } | null>(null);
  const [documents, setDocuments] = useState<BrowserDocument[]>([]);
  const [viewer, setViewer] = useState<{ document: BrowserDocument; url: string } | null>(null);
  const [feedback, setFeedback] = useState("");
  const [kind, setKind] = useState<"primary" | "backup">("primary");
  useEffect(() => {
    let active = true;
    let stop: (() => void) | undefined;
    setDrafts({});
    const failed = () => {
      if (!active) return;
      onChange(null);
    };
    void observePlan(plan.ownerUid, (value) => { if (active) onChange(value); }, failed)
      .then((cleanup) => { if (active) stop = cleanup; else cleanup(); }).catch(failed);
    return () => { active = false; stop?.(); };
  }, [plan.ownerUid, onChange]);
  useEffect(() => {
    if (!identity) return;
    let active = true;
    let stop: (() => void) | undefined;
    const failed = () => { if (active) setFeedback("Unable to load encrypted documents."); };
    void observeBrowserDocuments(identity, (rows) => { if (active) setDocuments(rows); }, failed)
      .then((cleanup) => { if (active) stop = cleanup; else cleanup(); }).catch(failed);
    return () => { active = false; stop?.(); };
  }, [identity?.uid]);
  useEffect(() => {
    if (viewer) return () => URL.revokeObjectURL(viewer.url);
  }, [viewer]);
  useEffect(() => {
    if (viewer && !documents.some((file) => file.id === viewer.document.id && file.approved)) setViewer(null);
  }, [viewer, documents]);
  const saveStep = async (key: string, itemId: string, change: { text?: string; checked?: boolean }, revision?: number, baseValue?: string) => {
    const items = readSafetyChecklist(plan.fields[key]?.value ?? "");
    const item = items.find((row) => row.id === itemId);
    if (!item) throw new Error("This step was removed. Review the updated plan.");
    const result = await saveField(plan, key, writeSafetyChecklist(
      items.map((current) => current.id === itemId ? { ...current, ...change } : current),
    ), revision, baseValue);
    onChange(result.plan);
    setFeedback(result.conflicts.length ? "The plan owner will resolve the conflicting edit." : "");
    setDrafts((current) => {
      const next = { ...current };
      delete next[`${key}:${itemId}`];
      return next;
    });
  };
  useEffect(() => {
    if (session.busy) return;
    const next = Object.entries(drafts).find(([key, draft]) =>
      lastAttempt.current?.draftKey !== key || lastAttempt.current.text !== draft.text,
    );
    if (!next) return;
    const [draftKey, draft] = next;
    const timer = window.setTimeout(() => {
      lastAttempt.current = { draftKey, text: draft.text };
      void session.perform(() => saveStep(draft.key, draft.itemId, { text: draft.text }, draft.revision, draft.baseValue));
    }, 75);
    return () => window.clearTimeout(timer);
  }, [drafts, plan, session.busy]);
  return (
    <section className="panel safety-plan-group">
      <h2>{plan.ticketNumber !== undefined && plan.ticketNumber >= 0 ? `Consult Ticket #${plan.ticketNumber}` : "Consult Ticket"}</h2>
      <div className="safety-plan-tabs" aria-label="Safety plans" role="group">
        {(["primary", "backup"] as const).map((planKind) => (
          <button key={planKind} type="button" aria-pressed={planKind === kind} onClick={() => setKind(planKind)}>
            {planKind === "primary" ? "Primary plan" : "Backup plan"}
          </button>
        ))}
      </div>
      <div className="safety-checklist-stack">
        {[...safetySections, ...olderSafetySections.filter((section) =>
          readSafetyChecklist(plan.fields[`${kind}_${section}`]?.value ?? "").length > 0,
        )].map((section) => {
            const key = `${kind}_${section}`;
            const field = plan.fields[key];
            const items = readSafetyChecklist(field?.value ?? "");
            return (
              <fieldset className="safety-checklist" key={key}>
                <legend>{safetySectionLabels[section]}</legend>
                {items.map((item) => {
                  const draftKey = `${key}:${item.id}`;
                  const draft = drafts[draftKey];
                  return <div key={item.id}>
                    <label className="safety-checklist-row">
                      <input type="checkbox" checked={item.checked} disabled={session.busy}
                        aria-label={`Complete ${item.text}`} onChange={(event) => {
                          const checked = event.currentTarget.checked;
                          void session.perform(() => saveStep(key, item.id, { checked }));
                        }} />
                      {item.id.startsWith("trusted-contact:") ? <span>{safetyChecklistText(item)}</span> :
                      <input type="text" value={draft?.text ?? item.text} maxLength={1000}
                        aria-label={`${kind} ${section} step`} readOnly={session.busy}
                        onChange={(event) => {
                          lastAttempt.current = null;
                          const text = event.currentTarget.value;
                          setDrafts((current) => ({ ...current, [draftKey]: {
                            key, itemId: item.id, text, revision: draft?.revision ?? field.revision,
                            baseValue: draft?.baseValue ?? field.value,
                          } }));
                        }}
                        onBlur={() => {
                          if (session.busy) return;
                          if (draft && draft.text !== item.text) {
                            void session.perform(() => saveStep(key, item.id, { text: draft.text }, draft.revision, draft.baseValue));
                          }
                          else if (draft) setDrafts((current) => {
                            const next = { ...current };
                            delete next[draftKey];
                            return next;
                          });
                        }} />}
                    </label>
                    {(item.attachmentIds ?? []).map((id) => {
                      const document = documents.find((file) => file.id === id && file.ownerUid === plan.ownerUid);
                      return document?.approved ? <button key={id} type="button" className="text-link" disabled={session.busy}
                        onClick={() => void session.perform(async () =>
                          setViewer({ document, url: await openBrowserDocument(document) }),
                        )}>{document.name}</button> : <span key={id}>Document unavailable</span>;
                    })}
                  </div>;
                })}
                <Button variant="quiet" disabled={session.busy || items.length >= 80} onClick={() => void session.perform(async () => {
                  const result = await saveField(plan, key, writeSafetyChecklist([
                    ...items, { id: crypto.randomUUID(), text: "", checked: false },
                  ]));
                  onChange(result.plan);
                  setFeedback(result.conflicts.length ? "The plan owner will resolve the conflicting edit." : "");
                })}>Add step</Button>
              </fieldset>
            );
        })}
      </div>
      {feedback && <p role="status">{feedback}</p>}
      {identity?.role === "STAFF" && plan.staffUid === identity.uid && plan.consultationConversationId && (
        <Button disabled={session.busy || Object.keys(drafts).length > 0} onClick={() => void session.perform(async () => {
          await completePlans(plan.consultationConversationId!);
          onChange(null);
          onCompleted?.();
        })}>Mark complete</Button>
      )}
      {viewer && <SafeMediaViewer {...viewer} onClose={() => setViewer(null)} />}
    </section>
  );
}
