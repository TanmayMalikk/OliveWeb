// Calculator cover users: shows graph controls and plotted equations.
import type { Dispatch, SetStateAction } from "react";
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Minus, Plus } from "lucide-react";

export type GraphResult = {
  points: [number, number | null][];
  derivative: string;
};

export default function GraphingWorkspace({
  centreX,
  centreY,
  degrees,
  draw,
  error,
  graph,
  graphInput,
  points,
  setCentreX,
  setCentreY,
  setDegrees,
  setGraphInput,
  setTable,
  setZoom,
  width,
  zoom,
}: {
  centreX: number;
  centreY: number;
  degrees: boolean;
  draw: () => Promise<void>;
  error: string;
  graph: GraphResult | null;
  graphInput: string;
  points: [number, number][];
  setCentreX: Dispatch<SetStateAction<number>>;
  setCentreY: Dispatch<SetStateAction<number>>;
  setDegrees: Dispatch<SetStateAction<boolean>>;
  setGraphInput: Dispatch<SetStateAction<string>>;
  setTable: Dispatch<SetStateAction<boolean>>;
  setZoom: Dispatch<SetStateAction<number>>;
  width: number;
  zoom: number;
}) {
  return (
        <section
          className="graphing-workspace"
          aria-label="Graphing calculator"
        >
          <aside className="graph-expression-panel">
            <div className="graph-expression-heading">
              <div>
                <span className="eyebrow">EXPRESSIONS</span>
                <h2>Graph</h2>
              </div>
              <button
                type="button"
                className="angle-mode"
                onClick={() => setDegrees(!degrees)}
                aria-label={`Use ${degrees ? "radians" : "degrees"}`}
              >
                {degrees ? "DEG" : "RAD"}
              </button>
            </div>
            <form
              className="graph-expression-row"
              onSubmit={(event) => {
                event.preventDefault();
                void draw();
              }}
            >
              <span className="graph-expression-number">1</span>
              <span className="graph-expression-colour" aria-hidden="true" />
              <label htmlFor="graph-expression" className="sr-only">
                Graph expression
              </label>
              <span className="graph-y-label" aria-hidden="true">
                y =
              </span>
              <input
                id="graph-expression"
                value={graphInput}
                onChange={(event) =>
                  setGraphInput(event.target.value.slice(0, 180))
                }
                autoComplete="off"
                spellCheck={false}
                aria-describedby="graph-expression-help"
              />
              <button type="submit">Plot</button>
            </form>
            <div className="graph-expression-details">
              <p id="graph-expression-help">
                Try x^2, sin(x), sqrt(x), or a combination.
              </p>
              <p>
                <span>Derivative</span>
                <strong>{graph?.derivative ?? "—"}</strong>
              </p>
            </div>
            <button
              type="button"
              className="graph-table-button"
              onClick={() => setTable(true)}
            >
              Table &amp; analysis
            </button>
          </aside>

          <div className="graph-stage">
            <svg
              className="graph"
              viewBox="0 0 900 600"
              preserveAspectRatio="none"
              role="img"
              aria-label={`Graph of ${graphInput}. Zoom ${Math.round(zoom * 100)} percent. Center X ${centreX}, Y ${centreY}.`}
            >
              <defs>
                <pattern
                  id="minor-grid"
                  width="45"
                  height="30"
                  patternUnits="userSpaceOnUse"
                >
                  <path
                    d="M45 0H0V30"
                    fill="none"
                    stroke="#e4e7df"
                    strokeWidth="1"
                  />
                </pattern>
                <pattern
                  id="major-grid"
                  width="90"
                  height="60"
                  patternUnits="userSpaceOnUse"
                >
                  <rect width="90" height="60" fill="url(#minor-grid)" />
                  <path
                    d="M90 0H0V60"
                    fill="none"
                    stroke="#cfd5ca"
                    strokeWidth="1.2"
                  />
                </pattern>
              </defs>
              <rect width="900" height="600" fill="url(#major-grid)" />
              <path
                d={`M0 ${300 + (centreY / width) * 300}H900M${450 - (centreX / width) * 450} 0V600`}
                stroke="#697367"
                strokeWidth="1.4"
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
                    x1={450 + ((previous[0] - centreX) / width) * 450}
                    y1={300 - ((previous[1] - centreY) / width) * 300}
                    x2={450 + ((point[0] - centreX) / width) * 450}
                    y2={300 - ((point[1] - centreY) / width) * 300}
                    stroke="#3f6b43"
                    strokeWidth="3"
                    vectorEffect="non-scaling-stroke"
                  />
                );
              })}
            </svg>
            <div
              className="graph-zoom-controls"
              aria-label="Graph zoom controls"
            >
              <button
                type="button"
                onClick={() => setZoom((z) => Math.min(5, z * 1.25))}
                aria-label="Zoom in"
              >
                <Plus size={20} />
              </button>
              <button
                type="button"
                onClick={() => setZoom((z) => Math.max(0.2, z / 1.25))}
                aria-label="Zoom out"
              >
                <Minus size={20} />
              </button>
            </div>
            <div className="graph-pan-controls" aria-label="Graph pan controls">
              <button
                type="button"
                onClick={() => setCentreY((y) => y + width / 4)}
                aria-label="Pan up"
              >
                <ArrowUp size={18} />
              </button>
              <button
                type="button"
                onClick={() => setCentreX((x) => x - width / 4)}
                aria-label="Pan left"
              >
                <ArrowLeft size={18} />
              </button>
              <button
                type="button"
                onClick={() => {
                  setZoom(1);
                  setCentreX(0);
                  setCentreY(0);
                }}
                aria-label="Reset graph view"
              >
                0
              </button>
              <button
                type="button"
                onClick={() => setCentreX((x) => x + width / 4)}
                aria-label="Pan right"
              >
                <ArrowRight size={18} />
              </button>
              <button
                type="button"
                onClick={() => setCentreY((y) => y - width / 4)}
                aria-label="Pan down"
              >
                <ArrowDown size={18} />
              </button>
            </div>
            {error && (
              <p className="graph-error form-error" role="alert">
                {error}
              </p>
            )}
          </div>
        </section>
  );
}

