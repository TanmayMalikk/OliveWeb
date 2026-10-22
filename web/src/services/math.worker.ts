import { derivative, mean, median, std, format } from "mathjs";
import { safeExpression, calculateTool } from "./advanced";
self.onmessage = (
  event: MessageEvent<{
    id: number;
    type: string;
    source: string;
    zoom?: number;
    centreX?: number;
    degrees?: boolean;
    tool?: string;
  }>,
) => {
  const { id, type, source } = event.data;
  try {
    if (type === "advanced") {
      const result = calculateTool(
        event.data.tool ?? "Expression",
        source,
        event.data.degrees,
      );
      self.postMessage({
        id,
        result:
          typeof result === "string" ? result : JSON.stringify(result, null, 2),
      });
      return;
    }
    if (type === "stats") {
      const values = source
        .split(/[\s,;]+/)
        .filter(Boolean)
        .map(Number);
      if (
        !values.length ||
        values.length > 500 ||
        values.some((v) => !Number.isFinite(v))
      )
        throw new Error("Enter 1–500 finite numbers separated by commas.");
      self.postMessage({
        id,
        result: {
          count: values.length,
          mean: mean(values),
          median: median(values),
          std: values.length > 1 ? std(values) : 0,
          min: Math.min(...values),
          max: Math.max(...values),
          values,
        },
      });
      return;
    }
    if (type === "quadratic") {
      const numbers = source.split(",").map(Number);
      if (
        numbers.length !== 3 ||
        numbers.some((n) => !Number.isFinite(n)) ||
        numbers[0] === 0
      )
        throw new Error("Enter coefficients a, b, c, with a ≠ 0.");
      const [a, b, c] = numbers;
      const d = b * b - 4 * a * c;
      self.postMessage({
        id,
        result:
          d < 0
            ? `No real roots. Discriminant = ${d}`
            : `x = ${(-b + Math.sqrt(d)) / (2 * a)}, ${(-b - Math.sqrt(d)) / (2 * a)}`,
      });
      return;
    }
    const safe = safeExpression(source, event.data.degrees);
    const node = safe.node;
    const compiled = node.compile();
    const angle = event.data.degrees ? Math.PI / 180 : 1;
    const scope = {
      sin: (v: number) => Math.sin(v * angle),
      cos: (v: number) => Math.cos(v * angle),
      tan: (v: number) => Math.tan(v * angle),
    };
    if (type === "graph") {
      const width = 10 / (event.data.zoom ?? 1);
      const center = event.data.centreX ?? 0;
      const points = Array.from({ length: 201 }, (_, i) => {
        const x = center - width + (2 * width * i) / 200;
        let y: number;
        try {
          y = Number(compiled.evaluate({ ...scope, x }));
        } catch {
          y = NaN;
        }
        return [x, Number.isFinite(y) ? y : null];
      });
      self.postMessage({
        id,
        result: { points, derivative: derivative(node, "x").toString() },
      });
    } else {
      const value = safe.evaluate();
      self.postMessage({ id, result: format(value, { precision: 12 }) });
    }
  } catch (error) {
    self.postMessage({
      id,
      error: error instanceof Error ? error.message : "Unable to calculate.",
    });
  }
};
