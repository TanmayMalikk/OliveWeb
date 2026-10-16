import { useEffect, useRef, useState } from "react";
import {
  Calculator as CalculatorIcon,
  Delete,
  History,
  Plus,
  Minus,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  ArrowDown,
  SlidersHorizontal,
} from "lucide-react";
import { Button, Field, Modal, Form } from "../components";
import { useSession } from "../session";
import tools from "../data/tools.json";
import { readCover, writeCover } from "../services/cover-storage";
import StatPlots from "./StatPlots";
type GraphResult = { points: [number, number | null][]; derivative: string };
export default function Calculator() {
  const session = useSession();
  const [expression, setExpression] = useState("");
  const [result, setResult] = useState("0");
  const [error, setError] = useState("");
  const [scientific, setScientific] = useState(false);
  const [degrees, setDegrees] = useState(false);
  const [tab, setTab] = useState("Calculate");
  const [history, setHistory] = useState<
    { expression: string; result: string }[]
  >([]);
  const [graph, setGraph] = useState<GraphResult | null>(null);
  const [graphInput, setGraphInput] = useState("x^2");
  const [zoom, setZoom] = useState(1);
  const [centreX, setCentreX] = useState(0);
  const [centreY, setCentreY] = useState(0);
  const [table, setTable] = useState(false);
  const [solver, setSolver] = useState<
    "quadratic" | "stats" | "advanced" | null
  >(null);
  const [tool, setTool] = useState("Expression");
  const [historyReady, setHistoryReady] = useState(false);
  const [plots, setPlots] = useState(false);
  const storageId = `${session.enrollment?.uid ?? "preview"}:calculator`;
  useEffect(() => {
    let live = true;
    void readCover<{ expression: string; result: string }[]>(storageId, [])
      .then((value) => {
        if (live) {
          setHistory(value);
          setHistoryReady(true);
        }
      })
      .catch(() => setHistoryReady(true));
    return () => {
      live = false;
    };
  }, [storageId]);
  useEffect(() => {
    if (historyReady) void writeCover(storageId, history).catch(() => {});
  }, [history, historyReady, storageId]);
  const [toolResult, setToolResult] = useState("");
  const worker = useRef<Worker | null>(null);
  const nextId = useRef(0);
  const pending = useRef(
    new Map<
      number,
      {
        resolve: (value: unknown) => void;
        reject: (error: Error) => void;
        timer: ReturnType<typeof setTimeout>;
      }
    >(),
  );
  function stopWorker() {
    worker.current?.terminate();
    worker.current = null;
    for (const request of pending.current.values()) {
      clearTimeout(request.timer);
      request.reject(
        new Error("Calculation timed out. Use a simpler expression."),
      );
    }
    pending.current.clear();
  }
  useEffect(() => () => stopWorker(), []);
  async function calculate(type: string, source: string): Promise<unknown> {
    if (!worker.current) {
      worker.current = new Worker(
        new URL("../services/math.worker.ts", import.meta.url),
        { type: "module" },
      );
      worker.current.onmessage = (event) => {
        const request = pending.current.get(event.data.id);
        if (!request) return;
        clearTimeout(request.timer);
        pending.current.delete(event.data.id);
        event.data.error
          ? request.reject(new Error(event.data.error))
          : request.resolve(event.data.result);
      };
      worker.current.onerror = () => stopWorker();
    }
    const id = ++nextId.current;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(stopWorker, 5000);
      pending.current.set(id, { resolve, reject, timer });
      worker.current!.postMessage({
        id,
        type,
        source,
        degrees,
        zoom,
        centreX,
        tool,
      });
    });
  }
  async function equals() {
    setError("");
    if (await session.searchTrigger(expression)) {
      setExpression("");
      return;
    }
    try {
      const value = String(await calculate("calculate", expression));
      setResult(value);
      setHistory((old) => [{ expression, result: value }, ...old].slice(0, 40));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function draw() {
    try {
      setError("");
      setGraph((await calculate("graph", graphInput)) as GraphResult);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  useEffect(() => {
    if (tab === "Graph") void draw();
  }, [zoom, centreX, degrees, tab]);
  const labels: Record<string, string> = {
    AC: "Clear all",
    "⌫": "Delete digit",
    "=": "Equals",
    "÷": "Divide",
    "×": "Multiply",
    "−": "Minus",
    "√(": "Square root",
    π: "Pi",
    "^": "Power",
    ".": "Decimal point",
    "(": "Open parenthesis",
    ")": "Close parenthesis",
  };
  const width = 10 / zoom;
  const points =
    graph?.points.filter(
      (p): p is [number, number] =>
        p[1] !== null &&
        Number.isFinite(p[1]) &&
        Math.abs(p[1] - centreY) < width * 2,
    ) ?? [];
  return (
    <div className="calculator-page">
      <header className="calculator-heading">
        <CalculatorIcon strokeWidth={1.3} />
        <div>
          <span className="eyebrow">A LITTLE ROOM FOR CLARITY</span>
          <h1>Better Calculator</h1>
        </div>
      </header>
      <nav className="cover-tabs" aria-label="Calculator tools">
        {["Calculate", "Graph", "History"].map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            aria-current={t === tab ? "page" : undefined}
          >
            {t}
          </button>
        ))}
        <button onClick={() => setSolver("quadratic")}>Equations</button>
        <button onClick={() => setSolver("stats")}>Statistics</button>
        <button onClick={() => setSolver("advanced")}>Math tools</button>
        <button onClick={() => setPlots(true)}>Stat plots</button>
      </nav>
      <div className="calculator-layout">
        <section className="calculator-body panel">
          {tab === "Calculate" ? (
            <>
              <div className="calculator-display">
                <label htmlFor="expression" className="sr-only">
                  Expression
                </label>
                <input
                  id="expression"
                  value={expression}
                  onChange={(e) => setExpression(e.target.value.slice(0, 180))}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") void equals();
                  }}
                  placeholder="0"
                  autoComplete="off"
                />
                <output aria-live="polite">{result}</output>
              </div>
              <div className="calculator-options">
                <Button
                  variant="quiet"
                  onClick={() => setScientific(!scientific)}
                  aria-pressed={scientific}
                >
                  <SlidersHorizontal size={16} /> Scientific
                </Button>
                <Button variant="quiet" onClick={() => setDegrees(!degrees)}>
                  {degrees ? "DEG" : "RAD"}
                </Button>
              </div>
              {scientific && (
                <div className="scientific-keys">
                  {[
                    "sin(",
                    "cos(",
                    "tan(",
                    "asin(",
                    "acos(",
                    "atan(",
                    "sqrt(",
                    "log(",
                    "log10(",
                    "exp(",
                    "π",
                    "^",
                  ].map((key) => (
                    <button
                      key={key}
                      onClick={() =>
                        setExpression((v) => (v + key).slice(0, 180))
                      }
                      aria-label={labels[key] ?? key.replace("(", "")}
                    >
                      {key}
                    </button>
                  ))}
                </div>
              )}
              <div className="calculator-keys">
                {[
                  "AC",
                  "(",
                  ")",
                  "⌫",
                  "7",
                  "8",
                  "9",
                  "÷",
                  "4",
                  "5",
                  "6",
                  "×",
                  "1",
                  "2",
                  "3",
                  "−",
                  "0",
                  ".",
                  "+",
                  "=",
                ].map((key) => (
                  <button
                    key={key}
                    className={
                      key === "="
                        ? "equals"
                        : ["÷", "×", "−"].includes(key)
                          ? "operator"
                          : ""
                    }
                    aria-label={labels[key] ?? key}
                    onClick={() => {
                      if (key === "AC") {
                        setExpression("");
                        setResult("0");
                        setError("");
                      } else if (key === "⌫")
                        setExpression((v) => v.slice(0, -1));
                      else if (key === "=") void equals();
                      else setExpression((v) => (v + key).slice(0, 180));
                    }}
                  >
                    {key}
                  </button>
                ))}
              </div>
            </>
          ) : tab === "History" ? (
            <div className="calculation-history">
              <h2>Your calculations</h2>
              {history.length ? (
                history.map((row, i) => (
                  <button
                    key={i}
                    onClick={() => {
                      setExpression(row.expression);
                      setResult(row.result);
                      setTab("Calculate");
                    }}
                  >
                    <span>{row.expression}</span>
                    <strong>{row.result}</strong>
                  </button>
                ))
              ) : (
                <p>Calculations from this session appear here.</p>
              )}
            </div>
          ) : (
            <>
              <Form onSubmit={draw}>
                <Field
                  label="y ="
                  value={graphInput}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setGraphInput(e.target.value)
                  }
                  maxLength={180}
                />
                <Button type="submit">Plot graph</Button>
              </Form>
              <svg
                className="graph"
                viewBox="0 0 600 400"
                role="img"
                aria-label={`Graph of ${graphInput}. Zoom ${Math.round(zoom * 100)} percent. Center X ${centreX}, Y ${centreY}.`}
              >
                <defs>
                  <pattern
                    id="grid"
                    width="30"
                    height="20"
                    patternUnits="userSpaceOnUse"
                  >
                    <path
                      d="M30 0H0V20"
                      fill="none"
                      stroke="#d8ddce"
                      strokeWidth=".6"
                    />
                  </pattern>
                </defs>
                <rect width="600" height="400" fill="url(#grid)" />
                <path
                  d={`M0 ${200 + (centreY / width) * 200}H600M${300 - (centreX / width) * 300} 0V400`}
                  stroke="#68795e"
                />
                {points.map((point, index) => {
                  const previous = points[index - 1];
                  if (
                    !previous ||
                    Math.abs(point[1] - previous[1]) > width ||
                    point[0] - previous[0] > width / 50
                  )
                    return null;
                  return (
                    <line
                      key={index}
                      x1={300 + ((previous[0] - centreX) / width) * 300}
                      y1={200 - ((previous[1] - centreY) / width) * 200}
                      x2={300 + ((point[0] - centreX) / width) * 300}
                      y2={200 - ((point[1] - centreY) / width) * 200}
                      stroke="#405b35"
                      strokeWidth="2.5"
                    />
                  );
                })}
              </svg>
              <div className="graph-controls">
                <Button
                  variant="outline"
                  onClick={() => setZoom((z) => Math.min(5, z * 1.25))}
                  aria-label="Zoom in"
                >
                  <Plus size={17} />
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setZoom((z) => Math.max(0.2, z / 1.25))}
                  aria-label="Zoom out"
                >
                  <Minus size={17} />
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setCentreX((x) => x - width / 4)}
                  aria-label="Pan left"
                >
                  <ArrowLeft size={17} />
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setCentreX((x) => x + width / 4)}
                  aria-label="Pan right"
                >
                  <ArrowRight size={17} />
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setCentreY((y) => y + width / 4)}
                  aria-label="Pan up"
                >
                  <ArrowUp size={17} />
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setCentreY((y) => y - width / 4)}
                  aria-label="Pan down"
                >
                  <ArrowDown size={17} />
                </Button>
                <Button variant="quiet" onClick={() => setTable(true)}>
                  Table & analysis
                </Button>
              </div>
              <p className="help-note">
                Derivative: {graph?.derivative ?? "—"}
              </p>
            </>
          )}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
        </section>
        <aside className="calculator-note">
          <span className="eyebrow">A LITTLE PERSPECTIVE</span>
          <h2>
            Not everything
            <br />
            needs to be
            <br />
            <em>complicated.</em>
          </h2>
          <p>
            Everyday calculations, a clearer view of a curve, or a fresh look at
            the numbers.
          </p>
          <div className="calculator-note-line" />
          <p>
            Trigonometry uses {degrees ? "degrees" : "radians"}. Calculations
            stay on this device.
          </p>
        </aside>
      </div>
      {table && (
        <Modal title="Graph table & analysis" onClose={() => setTable(false)}>
          <p>y = {graphInput}</p>
          <p>Derivative: {graph?.derivative}</p>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">X</th>
                  <th scope="col">Y</th>
                </tr>
              </thead>
              <tbody>
                {graph?.points
                  .filter((_, i) => i % 10 === 0)
                  .map(([x, y]) => (
                    <tr key={x}>
                      <td>{x.toFixed(3)}</td>
                      <td>{y === null ? "Undefined" : y.toFixed(4)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </Modal>
      )}
      {solver && (
        <Modal
          title={
            solver === "advanced"
              ? "Math tools"
              : solver === "quadratic"
                ? "Quadratic equation"
                : "Statistical summary"
          }
          onClose={() => {
            setSolver(null);
            setToolResult("");
          }}
        >
          <Form
            onSubmit={async (data) => {
              try {
                const value = await calculate(
                  solver,
                  String(data.get("input")),
                );
                setToolResult(
                  typeof value === "string"
                    ? value
                    : JSON.stringify(value, null, 2),
                );
              } catch (e) {
                setToolResult((e as Error).message);
              }
            }}
          >
            {solver === "advanced" && (
              <label className="field">
                <span>Calculation</span>
                <select
                  value={tool}
                  onChange={(e) => {
                    setTool(e.target.value);
                    setToolResult("");
                  }}
                >
                  {[...new Set(tools.map((t) => t.category))].map(
                    (category) => (
                      <optgroup key={category} label={category}>
                        {tools
                          .filter((t) => t.category === category)
                          .map((t) => (
                            <option value={t.id} key={t.id}>
                              {t.title}
                            </option>
                          ))}
                      </optgroup>
                    ),
                  )}
                </select>
              </label>
            )}
            <Field
              key={solver === "advanced" ? tool : solver}
              label={
                solver === "advanced"
                  ? "Calculation input"
                  : solver === "quadratic"
                    ? "Coefficients a, b, c"
                    : "Numbers separated by commas"
              }
              name="input"
              required
              maxLength={3000}
              hint={
                solver === "advanced"
                  ? `Example: ${tools.find((t) => t.id === tool)?.example}`
                  : solver === "quadratic"
                    ? "For x² − 5x + 6, enter: 1, -5, 6"
                    : "Example: 2, 4, 6, 8"
              }
            />
            <Button type="submit">Calculate</Button>
            <pre role="status" className="tool-result">
              {toolResult}
            </pre>
          </Form>
        </Modal>
      )}
      {plots && <StatPlots close={() => setPlots(false)} />}
    </div>
  );
}
