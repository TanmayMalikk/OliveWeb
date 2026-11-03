// All safe-app roles: shows and edits safety plans for allowed roles.
import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Button, Empty } from "../../../components/SharedComponents";
import { accounts, cloudMode } from "../../../services/AccountService";
import {
  consultation,
  loadPlan,
  observePlan,
  readSafetyChecklist,
  safetyChecklistText,
  safetySections,
  saveField,
  sharedPlans,
  writeSafetyChecklist,
  type SafetyChecklistItem,
  type SafetyPlan as Plan,
} from "../../../services/SafetyPlanService";
import { useSession } from "../../../session/SessionProvider";

// Changes a safety-plan field key into a readable section label.
const label = (value: string) =>
  value.replace(
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
  // Copy new plan data into the editor without replacing unsaved typing.
  useEffect(() => {
    if (!cloudMode || !identity) return;
    let live = true;
    let stop: (() => void) | undefined;
    const owner =
      identity.role === "SAFE_CONTACT"
        ? sharedPlans().then((plans) => plans[0]?.ownerUid ?? "")
        : Promise.resolve(identity.uid);
    void owner
      .then((uid) => {
        if (!uid)
          throw new Error("No safety plan is currently shared with you.");
        return loadPlan(uid);
      })
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
                if ((current[key] ?? oldValue) === oldValue)
                  next[key] = field.value;
              }
              return next;
            });
          },
          (error) => live && setFeedback(error.message),
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
  }, [identity?.uid]);
  if (!cloudMode)
    return (
      <Empty title="Safety plans use your shared account.">
        Connect this browser to Firebase to keep a plan in sync with the mobile
        app and trusted helpers.
      </Empty>
    );
  if (!plan)
    return (
      <Empty title="Opening your safety plan…">
        {feedback || "Loading the latest shared sections."}
      </Empty>
    );

  const updateSection = async (
    key: string,
    items: SafetyChecklistItem[],
    message: string,
  ) => {
    const value = writeSafetyChecklist(items);
    setDrafts((current) => ({ ...current, [key]: value }));
    const next = await saveField(plan, key, value);
    setPlan(next);
    setDrafts((current) => ({
      ...current,
      [key]: next.fields[key]?.value ?? value,
    }));
    setFeedback(message);
  };
  return (
    <div className="safety-plan-web">
      <section className="panel safety-plan-summary">
        <ShieldCheck size={28} />
        <div>
          <h2>
            {editable
              ? "Your safety plan."
              : `${plan.ownerName}'s shared plan.`}
          </h2>
          <p>
            Changes sync through the same protected Firebase functions used by
            the Android app.
          </p>
          {plan.staffName && <small>Consultation: {plan.staffName}</small>}
        </div>
        {editable && (
          <Button
            variant="outline"
            disabled={session.busy}
            onClick={() =>
              void session.perform(async () => {
                const next = await consultation(
                  plan.consultationRequested ? "stop" : "request",
                );
                setPlan(next);
              })
            }
          >
            {plan.consultationRequested
              ? "End consultation request"
              : "Request consultation"}
          </Button>
        )}
      </section>
      <div className="safety-plan-tabs" role="tablist" aria-label="Safety plans">
        {(["primary", "backup"] as const).map((kind) => (
          <button
            key={kind}
            type="button"
            role="tab"
            aria-selected={activeKind === kind}
            aria-controls={`${kind}-plan-panel`}
            onClick={() => setActiveKind(kind)}
          >
            {label(kind)} plan
          </button>
        ))}
      </div>
      {([activeKind] as const).map((kind) => (
        <section key={kind} className="panel safety-plan-group">
          <div id={`${kind}-plan-panel`} role="tabpanel">
          <h2>{label(kind)} plan</h2>
          <div className="form-grid">
            {safetySections.map((section) => {
              const key = `${kind}_${section}`;
              const items = readSafetyChecklist(drafts[key] ?? "");
              return (
                <fieldset className="safety-checklist" key={key}>
                  <legend>{label(section)}</legend>
                  <p className="safety-progress">
                    {items.filter((item) => item.checked).length} of {items.length} prepared
                  </p>
                  <div className="safety-checklist-items">
                    {items.map((item, index) => {
                      const linkedContact = item.id.startsWith("trusted-contact:");
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
                              value={item.text}
                              maxLength={1000}
                              aria-label={`${label(section)} step ${index + 1}`}
                              onChange={(event) => {
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
                                const changed = readSafetyChecklist(drafts[key] ?? "");
                                if (writeSafetyChecklist(changed) === plan.fields[key]?.value)
                                  return;
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
          </div>
        </section>
      ))}
      {feedback && <p role="status">{feedback}</p>}
    </div>
  );
}
