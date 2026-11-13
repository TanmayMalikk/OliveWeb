// All safe-app roles: shows and edits safety plans for allowed roles.
import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Button, Empty, Modal } from "../../../components/SharedComponents";
import { accounts, cloudMode } from "../../../services/AccountService";
import {
  consultation,
  loadPlan,
  observePlan,
  observeSafetyAccess,
  planMediaRecipients,
  resolvePlanConflict,
  readSafetyChecklist,
  safetyChecklistConflictPreview,
  safetyChecklistText,
  safetySections,
  olderSafetySections,
  safetySectionLabels,
  saveField,
  sharedPlans,
  sharePlanWithContacts,
  writeSafetyChecklist,
  type SafetyChecklistItem,
  type SafetyPlan as Plan,
} from "../../../services/SafetyPlanService";
import { useSession } from "../../../session/SessionProvider";
import {
  observeBrowserDocuments,
  observeDocumentRecipientDevices,
  openBrowserDocument,
  shareBrowserPlanDocument,
  uploadBrowserDocument,
  type BrowserDocument,
} from "../../../services/MediaService";
import { SafeMediaViewer } from "./SafeMediaViewer";
import { subscribeSocial, type Friend } from "../../../services/MessagingService";

// Changes a safety-plan field key into a readable section label.
const label = (value: string) =>
  safetySectionLabels[value] ?? value.replace(
    /(^|_)(\w)/g,
    (_, space, letter) => `${space ? " " : ""}${letter.toUpperCase()}`,
  );
export default function SafetyPlan() {
  const session = useSession();
  const identity = accounts.identity;
  const [plan, setPlan] = useState<Plan | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [feedback, setFeedback] = useState("");
  const [activeKind, setActiveKind] = useState<"primary" | "backup">("primary");
  const editable = identity?.role === "USER";
  const [documents, setDocuments] = useState<BrowserDocument[]>([]);
  const [viewer, setViewer] = useState<{ document: BrowserDocument; url: string } | null>(null);
  const [documentError, setDocumentError] = useState("");
  const [sharedOwnerUid, setSharedOwnerUid] = useState("");
  const [sharedOwners, setSharedOwners] = useState<Awaited<ReturnType<typeof sharedPlans>>>([]);
  const [friends, setFriends] = useState<Friend[]>([]);
  const [selectedContacts, setSelectedContacts] = useState<string[]>([]);
  const baseRevisions = useRef<Record<string, number>>({});
  const baseValues = useRef<Record<string, string>>({});
  const lastAttempt = useRef<{ key: string; value: string } | null>(null);
  const [localConflict, setLocalConflict] = useState<{ key: string; mine: string } | null>(null);
  const [keepMine, setKeepMine] = useState(false);
  const remoteConflictKey = Object.keys(plan?.pendingConflicts ?? {})[0];
  const conflictKey = localConflict?.key ?? remoteConflictKey;
  useEffect(() => setKeepMine(false), [conflictKey, plan?.pendingConflicts?.[conflictKey ?? ""]?.mutationId]);

  useEffect(() => {
    if (!identity || !cloudMode) return;
    let active = true;
    let stop: (() => void) | undefined;
    setDocuments([]);
    setDocumentError("");
    const failed = () => {
      if (active) setDocumentError("Unable to load your encrypted documents.");
    };
    void observeBrowserDocuments(identity, (files) => {
      if (active) {
        setDocuments(files);
        setDocumentError("");
      }
    }, failed).then((unsubscribe) => {
      if (active) stop = unsubscribe;
      else unsubscribe();
    }).catch(failed);
    return () => {
      active = false;
      stop?.();
    };
  }, [identity?.uid]);

  useEffect(() => {
    if (!viewer) return;
    return () => URL.revokeObjectURL(viewer.url);
  }, [viewer]);
  useEffect(() => {
    if (viewer && !documents.some((file) => file.id === viewer.document.id && file.approved)) setViewer(null);
  }, [documents, viewer]);
  useEffect(() => {
    if (!identity || !cloudMode || editable) return;
    let active = true;
    let stop: (() => void) | undefined;
    let request = 0;
    const refresh = () => {
      const token = ++request;
      void sharedPlans().then((plans) => {
        if (!active || token !== request) return;
        setSharedOwners(plans);
        setSharedOwnerUid((uid) => plans.some((item) => item.ownerUid === uid) ? uid : plans[0]?.ownerUid ?? "");
        setFeedback(plans.length ? "" : "No safety plan is currently shared with you.");
      }).catch(() => { if (active) setFeedback("Unable to load shared plans."); });
    };
    refresh();
    void observeSafetyAccess(identity.uid, refresh, () => undefined).then((cleanup) => {
      if (active) stop = cleanup;
      else cleanup();
    });
    return () => { active = false; stop?.(); };
  }, [identity?.uid, editable]);
  useEffect(() => {
    if (!identity || !cloudMode || !editable) return;
    let active = true;
    let stop: (() => void) | undefined;
    void subscribeSocial(identity.uid, (value) => {
      if (active) setFriends(value.friends);
    }, () => undefined).then((cleanup) => {
      if (active) stop = cleanup;
      else cleanup();
    }).catch(() => undefined);
    return () => { active = false; stop?.(); };
  }, [identity?.uid, editable]);
  const sharedContacts = JSON.stringify(plan?.safeContactUids ?? []);
  useEffect(() => setSelectedContacts(plan?.safeContactUids ?? []), [sharedContacts]);
  const attachedIds = plan ? [...new Set(Object.values(plan.fields).flatMap((field) =>
    readSafetyChecklist(field.value).flatMap((item) => item.attachmentIds ?? []),
  ))] : [];
  const attachmentScope = JSON.stringify([attachedIds, plan?.staffUid, plan?.safeContactUids]);
  useEffect(() => {
    if (!editable || !identity || !attachedIds.length) return;
    let active = true;
    let stop: (() => void) | undefined;
    let timer: number | undefined;
    void planMediaRecipients().then(async (recipients) => {
      if (!active || !recipients.length) return;
      const share = () => {
        if (!active) return;
        window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          void Promise.allSettled(attachedIds.map((id) => shareBrowserPlanDocument(id, identity, recipients)))
            .then((results) => {
              if (active && results.some((result) => result.status === "rejected")) {
                setDocumentError("Unable to share one or more attached documents.");
              }
            });
        }, 100);
      };
      share();
      const cleanup = await observeDocumentRecipientDevices(recipients, share);
      if (active) stop = cleanup;
      else cleanup();
    }).catch(() => { if (active) setDocumentError("Unable to share one or more attached documents."); });
    return () => {
      active = false;
      window.clearTimeout(timer);
      stop?.();
    };
  }, [editable, identity?.uid, attachmentScope]);
  // Copy new plan data into the editor without replacing unsaved typing.
  useEffect(() => {
    if (!cloudMode || !identity) return;
    let live = true;
    let stop: (() => void) | undefined;
    const ownerUid = editable ? identity.uid : sharedOwnerUid;
    setPlan(null);
    setViewer(null);
    setLocalConflict(null);
    baseRevisions.current = {};
    baseValues.current = {};
    if (!ownerUid) return;
    void loadPlan(ownerUid)
      .then(async (value) => {
        if (!live) return;
        setPlan(value);
        setDrafts(
          Object.fromEntries(
            Object.entries(value.fields).map(([key, field]) => [
              key,
              field.value,
            ]),
          ),
        );
        let latest = value;
        const cleanup = await observePlan(
          value.ownerUid,
          (remote) => {
            if (!live) return;
            const previous = latest;
            latest = remote;
            setPlan(remote);
            setDrafts((current) => {
              const next = { ...current };
              for (const [key, field] of Object.entries(remote.fields)) {
                const oldValue = previous.fields[key]?.value ?? "";
                if (!(key in baseRevisions.current) && (current[key] ?? oldValue) === oldValue)
                  next[key] = field.value;
              }
              return next;
            });
          },
          (error) => {
            if (!live) return;
            setFeedback(error.message);
            if (!editable) {
              setPlan(null);
              setViewer(null);
            }
          },
        );
        if (live) stop = cleanup;
        else cleanup();
      })
      .catch((error) => {
        if (live)
          setFeedback(
            error instanceof Error ? error.message : "Unable to open the plan.",
          );
      });
    return () => {
      live = false;
      stop?.();
    };
  }, [identity?.uid, sharedOwnerUid, editable]);
  const updateSection = async (
    key: string,
    items: SafetyChecklistItem[],
    message: string,
  ) => {
    if (!plan) return;
    const value = writeSafetyChecklist(items);
    if (value.length > 4000) throw new Error("This section is full. Remove a step or document before adding more.");
    setDrafts((current) => ({ ...current, [key]: value }));
    const { plan: next, conflicts, conflictValues } = await saveField(
      plan, key, value, baseRevisions.current[key], baseValues.current[key],
    );
    delete baseRevisions.current[key];
    delete baseValues.current[key];
    setPlan(next);
    if (conflicts.includes(key) && next.fields[key]?.value !== value) {
      setLocalConflict({ key, mine: conflictValues?.[key] ?? value });
      return;
    }
    setDrafts((current) => ({
      ...current,
      [key]: next.fields[key]?.value ?? value,
    }));
    setFeedback(message);
  };

  useEffect(() => {
    if (!editable || !plan || session.busy || conflictKey) return;
    const key = Object.keys(baseRevisions.current).find((field) =>
      drafts[field] !== plan.fields[field]?.value &&
      (lastAttempt.current?.key !== field || lastAttempt.current.value !== drafts[field]),
    );
    if (!key) return;
    const timer = window.setTimeout(() => {
      lastAttempt.current = { key, value: drafts[key] };
      void session.perform(() => updateSection(key, readSafetyChecklist(drafts[key]), `${label(key)} saved.`));
    }, 75);
    return () => window.clearTimeout(timer);
  }, [drafts, plan, editable, session.busy, conflictKey]);

  if (!cloudMode)
    return (
      <Empty title="Safety plans use your shared account.">
        Connect this browser to Firebase to keep a plan in sync with the mobile
        app and trusted helpers.
      </Empty>
    );
  if (!plan)
    return (
      <Empty title={!editable && feedback ? feedback : "Opening safety plans…"}>
        {editable && feedback ? feedback : !feedback ? "Loading the latest sections." : null}
      </Empty>
    );

  const changeAttachment = (
    key: string, items: SafetyChecklistItem[], itemId: string,
    documentId: string, attach: boolean,
  ) => updateSection(key, items.map((item) => {
    if (item.id !== itemId) return item;
    const attachmentIds = (item.attachmentIds ?? []).filter((id) => id !== documentId);
    const excludedAttachmentIds = (item.excludedAttachmentIds ?? []).filter((id) => id !== documentId);
    return {
      ...item,
      attachmentIds: attach ? [...attachmentIds, documentId].slice(-80) : attachmentIds,
      excludedAttachmentIds: attach ? excludedAttachmentIds : [...excludedAttachmentIds, documentId].slice(-80),
    };
  }), attach ? "Document attached." : "Document removed from this step.");

  const kind = activeKind;
  return (
    <div className="safety-plan-web">
      {!editable && (
        <section className="panel shared-plan-heading">
          <div>
            <h2>{plan.ownerName ? plan.ownerName + "’s safety plan" : "Shared safety plan"}</h2>
            <p>View the primary and backup plans shared with you.</p>
          </div>
          {sharedOwners.length > 1 && (
            <label className="field">
              <span>Choose a shared plan</span>
              <select value={sharedOwnerUid} onChange={(event) => setSharedOwnerUid(event.currentTarget.value)}>
                {sharedOwners.map((owner) => (
                  <option key={owner.ownerUid} value={owner.ownerUid}>{owner.ownerName || "Shared plan"}</option>
                ))}
              </select>
            </label>
          )}
        </section>
      )}
      <div className="safety-workspace">
        <div className="safety-workspace-nav">
          <div className="safety-plan-tabs" role="tablist" aria-label="Safety plans">
            {(["primary", "backup"] as const).map((kind) => (
              <button
                key={kind}
                type="button"
                id={kind + "-plan-tab"}
                role="tab"
                tabIndex={activeKind === kind ? 0 : -1}
                aria-selected={activeKind === kind}
                aria-controls={activeKind === kind ? `${kind}-plan-panel` : undefined}
                onClick={() => setActiveKind(kind)}
                onKeyDown={(event) => {
                  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
                  event.preventDefault();
                  const next = event.key === "Home" ? "primary" : event.key === "End" ? "backup"
                    : kind === "primary" ? "backup" : "primary";
                  setActiveKind(next);
                  document.getElementById(next + "-plan-tab")?.focus();
                }}
              >
                {label(kind)} plan
              </button>
            ))}
          </div>
          <p className="safety-rail-label" aria-hidden="true">Checklists</p>
          <nav className="safety-section-links" aria-label="Jump to a checklist">
            {safetySections.map((section) => (
              <button key={section} type="button" onClick={() => {
                const checklist = document.getElementById(`${activeKind}_${section}`);
                checklist?.focus({ preventScroll: true });
                checklist?.scrollIntoView({ block: "start" });
              }}>{section === "documents" ? "Documents" : section === "grab" ? "Quick-grab" : label(section)}</button>
            ))}
          </nav>
        </div>
        <section key={kind} className="safety-plan-group" id={kind + "-plan-panel"}
          role="tabpanel" aria-labelledby={kind + "-plan-tab"}>
          <div className="safety-checklist-stack">
            {[...safetySections, ...olderSafetySections.filter((section) =>
              readSafetyChecklist(drafts[`${kind}_${section}`] ?? "").length > 0,
            )].map((section) => {
              const key = `${kind}_${section}`;
              const items = readSafetyChecklist(drafts[key] ?? "");
              return (
                <fieldset className="safety-checklist" id={key} key={key} tabIndex={-1}>
                  <legend>{label(section)}</legend>
                  <p className="safety-progress">
                    {items.filter((item) => item.checked).length} of {items.length} prepared
                  </p>
                  <div className="safety-checklist-items">
                    {items.map((item, index) => {
                      const linkedContact = item.id.startsWith("trusted-contact:");
                      const attachmentTag = ("safety-plan:" + key + ":" + item.id).slice(0, 80);
                      const attachedIds = [...new Set([
                        ...(item.attachmentIds ?? []),
                        ...documents.filter((file) => file.tags?.includes(attachmentTag)).map((file) => file.id),
                      ])].filter((id) => !item.excludedAttachmentIds?.includes(id));
                      return (
                        <div className="safety-checklist-row" key={item.id}>
                          <input
                            type="checkbox"
                            checked={item.checked}
                            disabled={!editable}
                            aria-label={`${safetyChecklistText(item)}, ${item.checked ? "checked" : "unchecked"}`}
                            onChange={(event) => {
                              const changed = items.map((candidate) =>
                                candidate.id === item.id
                                  ? { ...candidate, checked: event.target.checked }
                                  : candidate,
                              );
                              void session.perform(() =>
                                updateSection(key, changed, `${label(section)} saved.`),
                              );
                            }}
                          />
                          {linkedContact || !editable ? (
                            <span className={item.checked ? "completed" : ""}>
                              {safetyChecklistText(item) || "Empty step"}
                            </span>
                          ) : (
                            <input
                              type="text"
                              readOnly={session.busy}
                              value={item.text}
                              maxLength={1000}
                              aria-label={`${label(section)} step ${index + 1}`}
                              onChange={(event) => {
                                lastAttempt.current = null;
                                baseRevisions.current[key] ??= plan.fields[key]?.revision ?? 0;
                                baseValues.current[key] ??= plan.fields[key]?.value ?? "";
                                const changed = items.map((candidate) =>
                                  candidate.id === item.id
                                    ? { ...candidate, text: event.target.value }
                                    : candidate,
                                );
                                setDrafts((current) => ({
                                  ...current,
                                  [key]: writeSafetyChecklist(changed),
                                }));
                              }}
                              onBlur={() => {
                                if (session.busy) return;
                                const changed = readSafetyChecklist(drafts[key] ?? "");
                                if (writeSafetyChecklist(changed) === plan.fields[key]?.value) {
                                  delete baseRevisions.current[key];
                                  delete baseValues.current[key];
                                  return;
                                }
                                void session.perform(() =>
                                  updateSection(key, changed, `${label(section)} saved.`),
                                );
                              }}
                            />
                          )}
                          {editable && !linkedContact && (
                            <button
                              type="button"
                              className="safety-remove-step"
                              aria-label={`Remove ${label(section)} step ${index + 1}`}
                              onClick={() =>
                                void session.perform(() =>
                                  updateSection(
                                    key,
                                    items.filter((candidate) => candidate.id !== item.id),
                                    `${label(section)} step removed.`,
                                  ),
                                )
                              }
                            >
                              Remove
                            </button>
                          )}
                          {section === "documents" && (
                            <div className="safety-step-documents">
                              {attachedIds.map((id) => {
                                const document = documents.find((file) => file.id === id && file.ownerUid === plan.ownerUid);
                                return (
                                  <div className="safety-step-document" key={id}>
                                    {document?.approved ? (
                                      <button type="button" className="text-link" disabled={session.busy}
                                        onClick={() => void session.perform(async () => {
                                          const url = await openBrowserDocument(document);
                                          setViewer({ document, url });
                                        })}>
                                        {document.name}
                                      </button>
                                    ) : <span>{document?.name ?? "Document unavailable"}</span>}
                                    {editable && (
                                      <button type="button" className="safety-remove-step" disabled={session.busy}
                                        aria-label={"Remove attachment " + (document?.name ?? "from step " + (index + 1))}
                                        onClick={() => void session.perform(() => changeAttachment(key, items, item.id, id, false))}>
                                        Remove
                                      </button>
                                    )}
                                  </div>
                                );
                              })}
                              {editable && identity && (
                                <>
                                  <label className="field">
                                    <span>Attach a saved document</span>
                                    <select aria-label={"Attach a saved document to step " + (index + 1)}
                                      value="" disabled={session.busy}
                                      onChange={(event) => {
                                        const id = event.currentTarget.value;
                                        if (id) void session.perform(() => changeAttachment(key, items, item.id, id, true));
                                      }}>
                                      <option value="">Choose a document</option>
                                      {documents.filter((file) => file.ownerUid === identity.uid && !attachedIds.includes(file.id))
                                        .map((file) => <option key={file.id} value={file.id}>{file.name}</option>)}
                                    </select>
                                  </label>
                                  <label className="button outline">
                                    Upload and attach
                                    <input type="file" className="sr-only" disabled={session.busy}
                                      aria-label={"Upload a document for step " + (index + 1)}
                                      onChange={(event) => {
                                        const file = event.currentTarget.files?.[0];
                                        event.currentTarget.value = "";
                                        if (!file) return;
                                        void session.perform(async () => {
                                          const id = await uploadBrowserDocument(file, identity);
                                          await changeAttachment(key, items, item.id, id, true);
                                        });
                                      }} />
                                  </label>
                                </>
                              )}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                  {editable && items.length < 80 && (
                    <Button
                      variant="quiet"
                      onClick={() =>
                        void session.perform(() =>
                          updateSection(
                            key,
                            [
                              ...items,
                              { id: crypto.randomUUID(), text: "", checked: false },
                            ],
                            `${label(section)} step added.`,
                          ),
                        )
                      }
                    >
                      Add step
                    </Button>
                  )}
                </fieldset>
              );
            })}
          </div>
        </section>
      </div>
      {editable && <section className="panel safety-plan-summary">
        <ShieldCheck size={28} />
        <div>
          <h2>Plan consultation</h2>
          {plan.staffUid && <small>A support staff member is working with you.</small>}
          {!plan.consultationRequested && Boolean(plan.plansCompletedAt) &&
            <p role="status">Your safety plan and backup plan are complete.</p>}
          {plan.consultationRequested && plan.consultationConversationId && identity && (
            <Button variant="quiet" onClick={() => {
              session.setRequestedConversation({ id: plan.consultationConversationId!, uid: identity.uid });
              session.setPage("Messages");
            }}>Open consultation ticket</Button>
          )}
        </div>
          <Button
            variant="outline"
            disabled={session.busy}
            onClick={() =>
              void session.perform(async () => {
                const next = await consultation(
                  plan.consultationRequested ? "stop" : "request",
                );
                setPlan(next);
                if (identity && next.consultationRequested && next.consultationConversationId) {
                  if (attachedIds.length) {
                    void planMediaRecipients().then((recipients) => Promise.allSettled(
                      attachedIds.map((id) => shareBrowserPlanDocument(id, identity, recipients)),
                    )).catch(() => undefined);
                  }
                  session.setRequestedConversation({ id: next.consultationConversationId, uid: identity.uid });
                  session.setPage("Messages");
                }
              })
            }
          >
            {plan.consultationRequested
              ? "End consultation request"
              : "Request consultation"}
          </Button>
      </section>}
      {editable && (friends.length > 0 || selectedContacts.length > 0) && (
        <section className="panel">
          <h3>Share with safe contacts</h3>
          {friends.map((friend) => (
            <label className="field" key={friend.friendUid}>
              <span><input type="checkbox" checked={selectedContacts.includes(friend.friendUid)}
                disabled={session.busy || (selectedContacts.length >= 10 && !selectedContacts.includes(friend.friendUid))}
                onChange={(event) => {
                  const checked = event.currentTarget.checked;
                  setSelectedContacts((current) => checked
                    ? [...current, friend.friendUid]
                    : current.filter((uid) => uid !== friend.friendUid));
                }} /> {friend.friendUsername}</span>
            </label>
          ))}
          <Button disabled={session.busy} onClick={() => void session.perform(async () => {
            const selected = friends.filter((friend) => selectedContacts.includes(friend.friendUid));
            setPlan(await sharePlanWithContacts(selected));
            setFeedback("Safe contact access updated.");
          })}>Save sharing</Button>
        </section>
      )}
      {documentError && <p className="error" role="status">{documentError}</p>}
      {feedback && <p role="status">{feedback}</p>}
      {viewer && <SafeMediaViewer {...viewer} onClose={() => setViewer(null)} />}
      {editable && conflictKey && (
        <Modal title="Conflicts">
          {[
            { mine: true, label: "Your change", value: localConflict?.mine ?? plan.fields[conflictKey]?.value ?? "" },
            { mine: false, label: "Incoming change", value: localConflict
              ? plan.fields[conflictKey]?.value ?? ""
              : plan.pendingConflicts?.[conflictKey]?.value ?? "" },
          ].map((choice) => (
            <label className="field" key={choice.label}>
              <span><input type="radio" name="safety-conflict" checked={keepMine === choice.mine}
                onChange={() => setKeepMine(choice.mine)} /> {choice.label}</span>
              {(() => {
                const conflicts = safetyChecklistConflictPreview(
                  localConflict?.mine ?? plan.fields[conflictKey]?.value ?? "",
                  localConflict
                    ? plan.fields[conflictKey]?.value ?? ""
                    : plan.pendingConflicts?.[conflictKey]?.value ?? "",
                  choice.mine,
                );
                return conflicts === null ? (
                  <span>{readSafetyChecklist(choice.value).map((item) =>
                    (item.checked ? "☑ " : "☐ ") + safetyChecklistText(item),
                  ).join(" · ") || "Empty section"}</span>
                ) : conflicts.map((conflict, index) => (
                  <span key={`${conflict.item}-${conflict.property}-${index}`}>
                    {conflict.item}: {conflict.property}: {conflict.value}
                  </span>
                ));
              })()}
            </label>
          ))}
          <Button disabled={session.busy} onClick={() => void session.perform(async () => {
            if (!localConflict) {
              setPlan(await resolvePlanConflict(plan, conflictKey, !keepMine));
              return;
            }
            const current = plan.fields[conflictKey]?.value ?? "";
            if (keepMine) {
              const result = await saveField(plan, conflictKey, localConflict.mine);
              setPlan(result.plan);
              if (result.conflicts.includes(conflictKey)) return;
              setDrafts((draft) => ({ ...draft, [conflictKey]: localConflict.mine }));
            } else {
              setDrafts((draft) => ({ ...draft, [conflictKey]: current }));
            }
            setLocalConflict(null);
          })}>{keepMine ? "Keep my change" : "Use incoming change"}</Button>
        </Modal>
      )}
    </div>
  );
}
