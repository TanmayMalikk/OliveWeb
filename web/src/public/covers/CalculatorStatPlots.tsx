// Calculator cover users: draws calculator statistics plots.
import { useState } from "react";
import { Modal, Field, Button, Form } from "../../components/SharedComponents";
const kinds = [
  "Histogram",
  "Box plot",
  "Dot plot",
  "Scatter",
  "XY line",
] as const;
export default function StatPlots({ close }: { close: () => void }) {
  const [kind, setKind] = useState<(typeof kinds)[number]>("Histogram");
  const [data, setData] = useState<number[]>([]);
  const [y, setY] = useState<number[]>([]);
  const [error, setError] = useState("");
  const paired = kind === "Scatter" || kind === "XY line";
  const parse = (source: string) => {
    const rows = source
      .split(/[\s,;]+/)
      .filter(Boolean)
      .map(Number);
    if (
      !rows.length ||
      rows.length > 500 ||
      rows.some((n) => !Number.isFinite(n))
    )
      throw new Error("Enter 1–500 finite numbers.");
    return rows;
  };
  const sorted = [...data].sort((a, b) => a - b);
  const min = sorted[0] ?? 0,
    max = sorted.at(-1) ?? 1;
  const span = max - min || 1;
  const bottom = 300,
    left = 40,
    plotWidth = 520;
  const x = (n: number) => left + ((n - min) / span) * plotWidth;
  const yMin = Math.min(0, ...y),
    yMax = Math.max(1, ...y),
    ySpan = yMax - yMin || 1;
  const py = (n: number) => bottom - ((n - yMin) / ySpan) * 250;
  const bins = Array.from(
    { length: Math.max(1, Math.ceil(Math.sqrt(data.length))) },
    () => 0,
  );
  data.forEach((v) => {
    bins[
      Math.min(bins.length - 1, Math.floor(((v - min) / span) * bins.length))
    ]++;
  });
  const highest = Math.max(1, ...bins);
  const q = (p: number) => {
    const i = (sorted.length - 1) * p;
    const a = Math.floor(i);
    return (
      (sorted[a] ?? 0) +
      ((sorted[a + 1] ?? sorted[a] ?? 0) - (sorted[a] ?? 0)) * (i - a)
    );
  };
  return (
    <Modal title="Statistical plots" wide onClose={close}>
      <Form
        onSubmit={(form) => {
          try {
            const values = parse(String(form.get("x")));
            const second = paired ? parse(String(form.get("y"))) : [];
            if (paired && values.length !== second.length)
              throw new Error("X and Y lists need the same number of values.");
            setData(values);
            setY(second);
            setError("");
          } catch (e) {
            setError((e as Error).message);
          }
        }}
      >
        <label className="field">
          <span>Plot type</span>
          <select
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as typeof kind);
              setData([]);
            }}
          >
            {kinds.map((k) => (
              <option key={k}>{k}</option>
            ))}
          </select>
        </label>
        <div className="form-grid">
          <Field
            label={paired ? "X values" : "Data values"}
            name="x"
            hint="Numbers separated by commas"
            required
            maxLength={4000}
          />
          {paired && (
            <Field label="Y values" name="y" required maxLength={4000} />
          )}
        </div>
        <Button type="submit">Draw plot</Button>
      </Form>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {data.length > 0 && (
        <>
          <svg
            className="graph"
            viewBox="0 0 600 350"
            role="img"
            aria-label={`${kind}: ${paired ? data.map((v, i) => `X ${v}, Y ${y[i]}`).join("; ") : data.join(", ")}`}
          >
            <path d="M40 30V300H570" fill="none" stroke="#7a8c68" />
            {kind === "Histogram" ? (
              bins.map((count, i) => (
                <rect
                  key={i}
                  x={left + (i * plotWidth) / bins.length + 2}
                  y={bottom - (count / highest) * 240}
                  width={Math.max(1, plotWidth / bins.length - 4)}
                  height={(count / highest) * 240}
                  fill="#718c58"
                />
              ))
            ) : kind === "Box plot" ? (
              <>
                <path
                  d={`M${x(min)} 150H${x(max)}M${x(min)} 130V170M${x(max)} 130V170`}
                  stroke="#435d35"
                />
                <rect
                  x={x(q(0.25))}
                  y="110"
                  width={Math.max(1, x(q(0.75)) - x(q(0.25)))}
                  height="80"
                  fill="#d8e4c7"
                  stroke="#435d35"
                />
                <line
                  x1={x(q(0.5))}
                  x2={x(q(0.5))}
                  y1="110"
                  y2="190"
                  stroke="#435d35"
                  strokeWidth="3"
                />
              </>
            ) : kind === "Dot plot" ? (
              sorted.map((v, i) => (
                <circle
                  key={i}
                  cx={x(v)}
                  cy={
                    280 - sorted.slice(0, i).filter((n) => n === v).length * 12
                  }
                  r="5"
                  fill="#435d35"
                />
              ))
            ) : (
              <>
                {kind === "XY line" && (
                  <polyline
                    points={data.map((v, i) => `${x(v)},${py(y[i])}`).join(" ")}
                    fill="none"
                    stroke="#435d35"
                    strokeWidth="2"
                  />
                )}
                {data.map((v, i) => (
                  <circle
                    key={i}
                    cx={x(v)}
                    cy={py(y[i])}
                    r="4"
                    fill="#435d35"
                  />
                ))}
              </>
            )}
            <text x="40" y="328" fontSize="13" fill="#435d35">
              {min}
            </text>
            <text x="550" y="328" fontSize="13" fill="#435d35">
              {max}
            </text>
          </svg>
          <div className="table-scroll">
            <table>
              <caption>Plot data</caption>
              <thead>
                <tr>
                  <th scope="col">{paired ? "X" : "Value"}</th>
                  {paired && <th scope="col">Y</th>}
                </tr>
              </thead>
              <tbody>
                {data.map((v, i) => (
                  <tr key={i}>
                    <td>{v}</td>
                    {paired && <td>{y[i]}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Modal>
  );
}
