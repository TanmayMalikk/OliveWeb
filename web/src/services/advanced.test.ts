// Tests the test behavior.
import { describe, it, expect } from "vitest";
import { calculateTool, safeExpression } from "./advanced";
import tools from "../data/tools.json";
describe("mobile math-tool examples", () => {
  for (const tool of tools)
    it(`${tool.title} accepts its mobile example`, () => {
      expect(() =>
        calculateTool(tool.id, tool.example.split(" or ")[0]),
      ).not.toThrow();
    });
  it("computes calculus, matrix, regression and complex results", () => {
    expect(calculateTool("Integral", "x^2 ; 0 ; 3")).toBeCloseTo(9, 7);
    expect(calculateTool("Zero", "x^2-4 ; 0 ; 5")).toBeCloseTo(2, 7);
    expect(calculateTool("MatrixDeterminant", "1,2;3,4")).toBeCloseTo(-2, 7);
    expect(
      calculateTool("PolynomialRegression", "degree 2 | 1,2,3 | 2,5,10"),
    ).toMatchObject({ coefficients: expect.any(Array) });
    expect(safeExpression("asin(1)", true).evaluate()).toBeCloseTo(90, 8);
  });
  it("does not expose assignment, property access or function imports", () => {
    for (const input of ["a=3", "x.constructor", "import(1)", "[1,2]"])
      expect(() => safeExpression(input)).toThrow();
  });
});
