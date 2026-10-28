// Contains the safety plan editor code for this part of Olive.
import { safetySections, saveField, type SafetyPlan } from "../../services/safety-plans";
import { useSession } from "../../session";

// Groups the data and actions for safety plan editor props.
interface SafetyPlanEditorProps {
  plan: SafetyPlan;
  onChange: (plan: SafetyPlan) => void;
}

export default function SafetyPlanEditor({ plan, onChange }: SafetyPlanEditorProps) {
  const session = useSession();
  return (
    <section className="panel safety-plan-group">
      <h2>{plan.ownerName || "Shared"} plan</h2>
      <div className="form-grid">
        {(["primary", "backup"] as const).flatMap((kind) =>
          safetySections.map((section) => {
            const key = `${kind}_${section}`;
            return (
              <label className="field" key={key}>
                <span>
                  {kind} · {section}
                </span>
                <textarea
                  maxLength={4000}
                  defaultValue={plan.fields[key]?.value ?? ""}
                  onBlur={(event) => {
                    if (event.target.value === plan.fields[key]?.value) return;
                    void session.perform(async () =>
                      onChange(await saveField(plan, key, event.target.value)),
                    );
                  }}
                />
              </label>
            );
          }),
        )}
      </div>
    </section>
  );
}
