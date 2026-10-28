// All safe-app roles: shows and edits safety plans for allowed roles.
import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Button, Empty } from "../../../components/SharedComponents";
import { accounts, cloudMode } from "../../../services/AccountService";
import {
  consultation,
  loadPlan,
  safetySections,
  saveField,
  sharedPlans,
  type SafetyPlan as Plan,
} from "../../../services/SafetyPlanService";
import { useSession } from "../../../session/SessionProvider";

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
  const editable = identity?.role === "USER";
  useEffect(() => {
    if (!cloudMode || !identity) return;
    let live = true;
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
      .then((value) => {
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
      })
      .catch((error) => {
        if (live)
          setFeedback(
            error instanceof Error ? error.message : "Unable to open the plan.",
          );
      });
    return () => {
      live = false;
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
      {(["primary", "backup"] as const).map((kind) => (
        <section key={kind} className="panel safety-plan-group">
          <h2>{label(kind)} plan</h2>
          <div className="form-grid">
            {safetySections.map((section) => {
              const key = `${kind}_${section}`;
              return (
                <label className="field" key={key}>
                  <span>{label(section)}</span>
                  <textarea
                    value={drafts[key] ?? ""}
                    readOnly={!editable}
                    maxLength={4000}
                    onChange={(event) =>
                      setDrafts({ ...drafts, [key]: event.target.value })
                    }
                    onBlur={() => {
                      if (!editable || drafts[key] === plan.fields[key]?.value)
                        return;
                      void session.perform(async () => {
                        const next = await saveField(
                          plan,
                          key,
                          drafts[key] ?? "",
                        );
                        setPlan(next);
                        setFeedback(`${label(section)} saved.`);
                      });
                    }}
                  />
                </label>
              );
            })}
          </div>
        </section>
      ))}
      {feedback && <p role="status">{feedback}</p>}
    </div>
  );
}
