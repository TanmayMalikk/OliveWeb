// All safe-app roles: lists safe-app activity for the account.
import { useState } from "react";
import { useSession } from "../../../session/SessionProvider";
import { Empty, Field } from "../../../components/SharedComponents";
// Formats date time.
export const formatDateTime = (time: number) =>
  new Date(time).toLocaleString([], {
    dateStyle: "medium",
    timeStyle: "short",
  });

export default function Activity() {
  const s = useSession();
  const [category, setCategory] = useState<
    "settings" | "triggers" | "checkouts"
  >("settings");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const rows = s.history[category]
    .filter(
      (e) =>
        (!from || e.timestamp >= new Date(from).getTime()) &&
        (!to || e.timestamp < new Date(to).getTime() + 86400000),
    )
    .sort((a, b) => b.timestamp - a.timestamp);
  return (
    <>
      <div className="page-toolbar">
        <div className="filter-row">
          {(["settings", "triggers", "checkouts"] as const).map((c) => (
            <button
              key={c}
              aria-pressed={category === c}
              onClick={() => setCategory(c)}
            >
              {c[0].toUpperCase() + c.slice(1)}
            </button>
          ))}
        </div>
        <div className="form-grid">
          <Field
            label="From date"
            type="date"
            value={from}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setFrom(e.target.value)
            }
          />
          <Field
            label="To date"
            type="date"
            value={to}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
              setTo(e.target.value)
            }
          />
        </div>
      </div>
      <div className="activity-list panel">
        {rows.map((row) => (
          <article key={row.id}>
            <span className="activity-dot" />
            <div>
              <h3>
                {row.action || row.source || row.error || "Account activity"}
              </h3>
              <p>
                {row.area ??
                  row.lastResult ??
                  row.foods?.map((f) => `${f.quantity} × ${f.food}`).join(", ")}
              </p>
              {row.changes?.map((change) => (
                <p key={change.field}>
                  {change.field}: {change.before || "Empty"} →{" "}
                  {change.after || "Empty"}
                </p>
              ))}
              {row.totalCents !== undefined && (
                <strong>${(row.totalCents / 100).toFixed(2)}</strong>
              )}
            </div>
            <time>{formatDateTime(row.timestamp)}</time>
          </article>
        ))}
        {!rows.length && (
          <Empty title="A little quiet here.">
            Activity will appear as you use your account.
          </Empty>
        )}
      </div>
    </>
  );
}


