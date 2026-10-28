// Contains the advanced code for this part of Olive.
import * as math from "mathjs";
const allowed = new Set([
  "sin",
  "cos",
  "tan",
  "asin",
  "acos",
  "atan",
  "sqrt",
  "cbrt",
  "abs",
  "log",
  "log10",
  "ln",
  "exp",
  "floor",
  "ceil",
  "round",
  "factorial",
  "min",
  "max",
  "nCr",
  "nPr",
  "complex",
]);
export function safeExpression(source: string, degrees = false) {
  if (source.length > 180)
    throw new Error("Use at most 180 characters per expression.");
  const node = math.parse(
    source
      .replaceAll("×", "*")
      .replaceAll("÷", "/")
      .replaceAll("π", "pi")
      .replaceAll("−", "-"),
  );
  let count = 0;
  node.traverse((n) => {
    if (
      ++count > 120 ||
      ![
        "OperatorNode",
        "ConstantNode",
        "SymbolNode",
        "FunctionNode",
        "ParenthesisNode",
      ].includes(n.type)
    )
      throw new Error(
        "Use a mathematical expression without assignments or objects.",
      );
    if (
      n.type === "SymbolNode" &&
      !["pi", "e", "x", "i", ...allowed].includes(
        (n as unknown as { name: string }).name,
      )
    )
      throw new Error("Unknown symbol.");
  });
  const a = degrees ? Math.PI / 180 : 1;
  const compiled = node.compile();
  const scope = {
    sin: (v: number) => Math.sin(v * a),
    cos: (v: number) => Math.cos(v * a),
    tan: (v: number) => Math.tan(v * a),
    asin: (v: number) => Math.asin(v) / a,
    acos: (v: number) => Math.acos(v) / a,
    atan: (v: number) => Math.atan(v) / a,
    ln: math.log,
    nCr: math.combinations,
    nPr: math.permutations,
  };
  return { node, evaluate: (x = 0) => compiled.evaluate({ ...scope, x }) };
}
function number(source: string, degrees = false) {
  const value = Number(safeExpression(source, degrees).evaluate());
  if (!Number.isFinite(value)) throw new Error("Expected a finite number.");
  return value;
}
function values(source: string) {
  const values = source
    .split(/[\s,]+/)
    .filter(Boolean)
    .map(Number);
  if (
    !values.length ||
    values.length > 500 ||
    values.some((v) => !Number.isFinite(v))
  )
    throw new Error("Enter 1–500 finite numbers.");
  return values;
}
function matrix(source: string) {
  const rows = source
    .split(";")
    .filter((s) => s.trim())
    .map((s) => values(s));
  if (
    !rows.length ||
    rows.length > 25 ||
    rows[0].length > 25 ||
    rows.some((row) => row.length !== rows[0].length)
  )
    throw new Error("Enter a rectangular matrix of at most 25 × 25 values.");
  return rows;
}
function rref(input: number[][]) {
  const a = input.map((row) => [...row]);
  let row = 0;
  for (let column = 0; column < a[0].length && row < a.length; column++) {
    let best = row;
    for (let i = row + 1; i < a.length; i++)
      if (Math.abs(a[i][column]) > Math.abs(a[best][column])) best = i;
    if (Math.abs(a[best][column]) < 1e-12) continue;
    [a[row], a[best]] = [a[best], a[row]];
    const pivot = a[row][column];
    a[row] = a[row].map((x) => x / pivot);
    for (let i = 0; i < a.length; i++)
      if (i !== row) {
        const factor = a[i][column];
        a[i] = a[i].map((x, j) => x - factor * a[row][j]);
      }
    row++;
  }
  return a.map((row) => row.map((x) => (Math.abs(x) < 1e-10 ? 0 : x)));
}
function range(a: number, b: number) {
  if (!(a < b) || Math.abs(a) > 1e9 || Math.abs(b) > 1e9)
    throw new Error("Enter finite lower and upper bounds, with lower < upper.");
}
function root(f: (x: number) => number, a: number, b: number) {
  range(a, b);
  let fa = f(a);
  const fb = f(b);
  if (Math.abs(fa) < 1e-10) return a;
  if (Math.abs(fb) < 1e-10) return b;
  if (!Number.isFinite(fa * fb))
    throw new Error("Choose finite function values at the bounds.");
  if (fa * fb > 0) {
    let previous = a,
      previousValue = fa;
    for (let i = 1; i <= 256; i++) {
      const point = a + ((b - a) * i) / 256,
        value = f(point);
      if (
        Number.isFinite(value) &&
        Number.isFinite(previousValue) &&
        previousValue * value <= 0
      )
        return root(f, previous, point);
      previous = point;
      previousValue = value;
    }
    throw new Error("No continuous zero was found in these bounds.");
  }
  for (let i = 0; i < 100; i++) {
    const c = (a + b) / 2;
    const fc = f(c);
    if (!Number.isFinite(fc))
      throw new Error("The function is not continuous in these bounds.");
    if (Math.abs(fc) < 1e-10) return c;
    if (fa * fc <= 0) b = c;
    else {
      a = c;
      fa = fc;
    }
  }
  return (a + b) / 2;
}
function extremum(
  f: (x: number) => number,
  a: number,
  b: number,
  maximum = false,
) {
  range(a, b);
  const direction = maximum ? -1 : 1;
  const ratio = (Math.sqrt(5) - 1) / 2;
  for (let i = 0; i < 100; i++) {
    const c = b - ratio * (b - a),
      d = a + ratio * (b - a);
    if (direction * f(c) < direction * f(d)) b = d;
    else a = c;
  }
  const x = (a + b) / 2;
  const y = f(x);
  if (!Number.isFinite(y))
    throw new Error("Function is undefined in this interval.");
  return { x, y };
}
function regression(x: number[], y: number[], degree = 1) {
  if (x.length !== y.length || x.length <= degree || degree < 1 || degree > 6)
    throw new Error(
      "Use matching lists, more points than the degree, and degree 1–6.",
    );
  const design = x.map((value) =>
    Array.from({ length: degree + 1 }, (_, i) => value ** i),
  );
  const transpose = math.transpose(design);
  const coefficients = math.multiply(
    math.inv(math.multiply(transpose, design)),
    math.multiply(transpose, y),
  ) as number[];
  const prediction = x.map((value) =>
    coefficients.reduce((sum, c, i) => sum + c * value ** i, 0),
  );
  const average = math.mean(y);
  const ss = y.reduce((sum, v) => sum + (v - average) ** 2, 0);
  const residual = y.reduce((sum, v, i) => sum + (v - prediction[i]) ** 2, 0);
  return { coefficients, rSquared: ss ? 1 - residual / ss : 1 };
}
// Calculates tool.
export function calculateTool(
  tool: string,
  input: string,
  degrees = false,
): unknown {
  if (input.length > 6000) throw new Error("Input is too long.");
  const semi = input.split(";").map((s) => s.trim());
  const pipes = input.split("|").map((s) => s.trim());
  const scalar = (s: string) => number(s, degrees);
  const f = (source: string) => {
    const fn = safeExpression(source, degrees);
    return (x: number) => Number(fn.evaluate(x));
  };
  switch (tool) {
    case "Expression":
      return safeExpression(input, degrees).evaluate();
    case "FunctionValue":
      return f(semi[0])(scalar(semi[1]));
    case "LinearSystem":
    case "MatrixReducedRowEchelonForm":
      return rref(matrix(input));
    case "Fraction":
      return math.fraction(scalar(input)).toFraction();
    case "MixedFraction": {
      const mixed = input.match(/^(-?\d+)\s+(\d+)\/(\d+)$/);
      const fraction = math.fraction(
        mixed
          ? Number(mixed[1]) +
              (Math.sign(Number(mixed[1]) || 1) * Number(mixed[2])) /
                Number(mixed[3])
          : scalar(input),
      );
      const n = Number(fraction.n),
        d = Number(fraction.d),
        sign = Number(fraction.s);
      return `${sign < 0 ? "-" : ""}${Math.floor(n / d)} ${n % d}/${d} (${fraction.toFraction()})`;
    }
    case "Scientific":
      return scalar(input).toExponential(10);
    case "Derivative": {
      const point = scalar(semi[1]);
      const h = 1e-5 * Math.max(1, Math.abs(point));
      const fn = f(semi[0]);
      return (fn(point + h) - fn(point - h)) / (2 * h);
    }
    case "Integral": {
      const fn = f(semi[0]);
      const a = scalar(semi[1]),
        b = scalar(semi[2]);
      range(a, b);
      const n = 2048;
      const h = (b - a) / n;
      let sum = fn(a) + fn(b);
      for (let i = 1; i < n; i++) sum += (i % 2 ? 4 : 2) * fn(a + i * h);
      const result = (sum * h) / 3;
      if (!Number.isFinite(result))
        throw new Error("The integral is undefined across these bounds.");
      return result;
    }
    case "Zero":
      return root(f(semi[0]), scalar(semi[1]), scalar(semi[2]));
    case "Minimum":
    case "Maximum":
      return extremum(
        f(semi[0]),
        scalar(semi[1]),
        scalar(semi[2]),
        tool === "Maximum",
      );
    case "Intersection": {
      const left = f(semi[0]),
        right = f(semi[1]);
      const x = root(
        (x) => left(x) - right(x),
        scalar(semi[2]),
        scalar(semi[3]),
      );
      return { x, y: left(x) };
    }
    case "Limit": {
      const fn = f(semi[0]),
        x = scalar(semi[1]),
        h = 1e-7 * Math.max(1, Math.abs(x));
      const left = fn(x - h),
        right = fn(x + h);
      return Number.isFinite(left) &&
        Number.isFinite(right) &&
        Math.abs(left - right) < 1e-4 * Math.max(1, Math.abs(left))
        ? { estimate: (left + right) / 2, left, right }
        : { estimate: "No matching finite one-sided limit", left, right };
    }
    case "ComplexMath":
      return safeExpression(
        input.replace(/\((-?[\d.]+),\s*(-?[\d.]+)\)/g, "complex($1,$2)"),
        degrees,
      ).evaluate();
    case "ComplexDetails": {
      const [re, im] = values(input);
      const z = math.complex(re, im ?? 0);
      return {
        value: z.toString(),
        magnitude: math.abs(z),
        argument: math.arg(z),
        conjugate: math.conj(z).toString(),
      };
    }
    case "ComplexPower": {
      const [re, im] = values(semi[0]);
      const exponent = scalar(semi[1]);
      const z = math.complex(re, im ?? 0);
      return {
        power: math.pow(z, exponent).toString(),
        roots:
          Number.isInteger(exponent) && exponent > 0 && exponent <= 32
            ? Array.from({ length: exponent }, (_, k) =>
                math
                  .complex({
                    r: Math.pow(Number(math.abs(z)), 1 / exponent),
                    phi: (Number(math.arg(z)) + 2 * Math.PI * k) / exponent,
                  })
                  .toString(),
              )
            : [],
      };
    }
    case "MatrixAdd":
      return pipes[2] === "-"
        ? math.subtract(matrix(pipes[0]), matrix(pipes[1]))
        : math.add(matrix(pipes[0]), matrix(pipes[1]));
    case "MatrixMultiply":
      return math.multiply(matrix(pipes[0]), matrix(pipes[1]));
    case "MatrixScalar":
      return math.multiply(matrix(pipes[0]), scalar(pipes[1]));
    case "MatrixPower": {
      const power = scalar(pipes[1]);
      if (!Number.isInteger(power) || Math.abs(power) > 20)
        throw new Error("Use an integer power between -20 and 20.");
      return math.pow(matrix(pipes[0]), power);
    }
    case "MatrixInverse":
      return math.inv(matrix(input));
    case "MatrixDeterminant":
      return math.det(matrix(input));
    case "MatrixTranspose":
      return math.transpose(matrix(input));
    case "MatrixIdentity": {
      const n = scalar(input);
      if (!Number.isInteger(n) || n < 1 || n > 25)
        throw new Error("Choose a matrix size from 1 to 25.");
      return Array.from({ length: n }, (_, i) =>
        Array.from({ length: n }, (_, j) => (i === j ? 1 : 0)),
      );
    }
    case "MatrixRowOperation": {
      const rows = matrix(pipes[0]);
      const [command, a, b, c] = pipes[1].split(/\s+/);
      const i = Number(a) - 1;
      if (!rows[i]) throw new Error("Invalid row.");
      if (command === "swap") {
        const j = Number(b) - 1;
        if (!rows[j]) throw new Error("Invalid row.");
        [rows[i], rows[j]] = [rows[j], rows[i]];
      } else if (command === "scale")
        rows[i] = rows[i].map((v) => v * scalar(b));
      else if (command === "add") {
        const j = Number(b) - 1;
        if (!rows[j]) throw new Error("Invalid row.");
        rows[i] = rows[i].map((v, k) => v + scalar(c) * rows[j][k]);
      } else
        throw new Error(
          "Use swap row row, scale row factor, or add target source factor.",
        );
      return rows;
    }
    case "OneVariableStats": {
      const v = values(input);
      return {
        count: v.length,
        sum: math.sum(v),
        mean: math.mean(v),
        median: math.median(v),
        sampleStd: v.length > 1 ? math.std(v) : 0,
        populationStd: math.std(v, "uncorrected"),
        min: Math.min(...v),
        q1: math.quantileSeq(v, 0.25),
        q3: math.quantileSeq(v, 0.75),
        max: Math.max(...v),
      };
    }
    case "TwoVariableStats": {
      const x = values(pipes[0]),
        y = values(pipes[1]);
      return {
        ...regression(x, y),
        meanX: math.mean(x),
        meanY: math.mean(y),
        correlation: math.corr(x, y),
      };
    }
    case "PolynomialRegression":
      return regression(
        values(pipes[1]),
        values(pipes[2]),
        Number(pipes[0].replace(/degree\s*/i, "")),
      );
    case "Regression": {
      const kind = pipes[0].toLowerCase();
      const x = values(pipes[1]),
        y = values(pipes[2]);
      if (kind === "linear") return regression(x, y);
      const transformedX =
        kind === "logarithmic" || kind === "power"
          ? x.map((v) => {
              if (v <= 0) throw new Error("X values must be positive.");
              return Math.log(v);
            })
          : x;
      const transformedY =
        kind === "exponential" || kind === "power"
          ? y.map((v) => {
              if (v <= 0) throw new Error("Y values must be positive.");
              return Math.log(v);
            })
          : y;
      const result = regression(transformedX, transformedY);
      return {
        model: kind,
        ...result,
        intercept:
          kind === "exponential" || kind === "power"
            ? Math.exp(result.coefficients[0])
            : result.coefficients[0],
        coefficient: result.coefficients[1],
      };
    }
    default:
      throw new Error("Choose a supported tool.");
  }
}
