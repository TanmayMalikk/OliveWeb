// Tests flight movement and collision rules for cover users.
import { describe, expect, it } from "vitest";
import {
  advanceFlight,
  FLIGHT,
  hitsLeafyBranch,
  newFlight,
  flightSpeedMultiplier,
  flightGap,
} from "./olive-flight";

describe("Olive Flight physics", () => {
  // Inputs: the values in this case. Expected: caps speed at 7.5x starting at 130 points.
  it("caps speed at 7.5x starting at 130 points", () => {
    expect(flightSpeedMultiplier(129)).toBe(7.4);
    for (const score of [130, 132, 10000]) {
      expect(flightSpeedMultiplier(score)).toBe(7.5);
      const world = { ...newFlight(), score };
      const next = advanceFlight(world, 0.01);
      expect(world.branches[0].x - next.branches[0].x).toBeCloseTo(
        FLIGHT.speed * 7.5 * 0.01,
      );
    }
  });
  // Inputs: the values in this case. Expected: narrows the opening every ten points and stops at two olive bodies.
  it("narrows the opening every ten points and stops at two olive bodies", () => {
    expect(flightGap(0)).toBe(174);
    expect(flightGap(9)).toBe(174);
    expect(flightGap(10)).toBe(166);
    expect(flightGap(19)).toBe(166);
    expect(flightGap(20)).toBe(158);
    expect(flightGap(110)).toBe(flightGap(10000));
    expect(flightGap(10000) - FLIGHT.branchStroke).toBe(FLIGHT.radiusY * 4);
  });
  // Inputs: the values in this case. Expected: only applies smaller gaps to newly arriving branches and resets on restart.
  it("only applies smaller gaps to newly arriving branches and resets on restart", () => {
    const world = {
      ...newFlight(),
      score: 20,
      branches: [
        { ...newFlight().branches[0], x: -80, passed: true },
        { ...newFlight().branches[1], x: 300 },
      ],
    };
    const next = advanceFlight(world, 0.01, () => 0.5);
    expect(next.branches[0].gap).toBe(174);
    expect(next.branches[1].gap).toBe(158);
    expect(newFlight().branches.every((branch) => branch.gap === 174)).toBe(
      true,
    );
  });
  // Inputs: the values in this case. Expected: uses each branch's own gap for collision detection.
  it("uses each branch's own gap for collision detection", () => {
    const world = {
      ...newFlight(),
      y: 280,
      branches: [{ x: 69, gapY: 230, gap: 174, passed: false }],
    };
    expect(advanceFlight(world, 0).over).toBe(false);
    expect(
      advanceFlight(
        {
          ...world,
          branches: [{ ...world.branches[0], gap: flightGap(1000) }],
        },
        0,
      ).over,
    ).toBe(true);
  });
  // Inputs: the values in this case. Expected: increases horizontal speed by 0.1x at each two-point milestone.
  it("increases horizontal speed by 0.1x at each two-point milestone", () => {
    for (const [score, multiplier] of [
      [0, 1],
      [1, 1],
      [2, 1.1],
      [3, 1.1],
      [4, 1.2],
      [6, 1.3],
      [20, 2],
    ]) {
      expect(flightSpeedMultiplier(score)).toBeCloseTo(multiplier);
      const world = { ...newFlight(), score };
      const advanced = advanceFlight(world, 0.02);
      expect(world.branches[0].x - advanced.branches[0].x).toBeCloseTo(
        FLIGHT.speed * multiplier * 0.02,
      );
      expect(advanced.y).toBeCloseTo(advanceFlight(newFlight(), 0.02).y);
    }
  });
  // Inputs: the values in this case. Expected: applies a newly earned milestone on the next frame and resets on a new round.
  it("applies a newly earned milestone on the next frame and resets on a new round", () => {
    const milestone = advanceFlight(
      {
        ...newFlight(),
        score: 1,
        branches: [
          { x: 20, gapY: 230, gap: FLIGHT.gap, passed: false },
          { x: 350, gapY: 238, gap: FLIGHT.gap, passed: false },
        ],
      },
      0.01,
    );
    expect(milestone.score).toBe(2);
    const next = advanceFlight(milestone, 0.01);
    expect(milestone.branches[1].x - next.branches[1].x).toBeCloseTo(
      FLIGHT.speed * 1.1 * 0.01,
    );
    expect(flightSpeedMultiplier(newFlight().score)).toBe(1);
  });
  // Inputs: the values in this case. Expected: fills wider play areas with regularly spaced branches.
  it("fills wider play areas with regularly spaced branches", () => {
    const wide = newFlight(1000);
    expect(wide.branches).toHaveLength(4);
    expect(wide.branches.map((branch) => branch.x)).toEqual([
      350, 600, 850, 1100,
    ]);
    const resized = advanceFlight(newFlight(), 0.01, () => 0.5, 1000);
    expect(resized.branches).toHaveLength(4);
    expect(resized.branches[3].x - resized.branches[2].x).toBeCloseTo(250);
  });
  // Inputs: the values in this case. Expected: does not collide with empty space beside the narrow branch tip.
  it("does not collide with empty space beside the narrow branch tip", () => {
    expect(hitsLeafyBranch(95, 230, false, 230, 0)).toBe(false);
    expect(hitsLeafyBranch(95, 230, true, 230, 0)).toBe(false);
    expect(hitsLeafyBranch(80, 230, false, 230, 0)).toBe(true);
    expect(hitsLeafyBranch(80, 230, true, 230, 0)).toBe(true);
  });
  // Inputs: the values in this case. Expected: detects visible leaves without filling the empty space between them.
  it("detects visible leaves without filling the empty space between them", () => {
    expect(hitsLeafyBranch(50, 200, false, 245, 0)).toBe(true);
    expect(hitsLeafyBranch(50, 200, false, 210, 0)).toBe(false);
    expect(hitsLeafyBranch(50, 280, true, 235, 0)).toBe(true);
    expect(hitsLeafyBranch(50, 280, true, 270, 0)).toBe(false);
  });
  // Inputs: the values in this case. Expected: flaps upward then falls under gravity.
  it("flaps upward then falls under gravity", () => {
    let world = { ...newFlight(), velocity: FLIGHT.lift };
    world = advanceFlight(world, 0.02);
    expect(world.y).toBeLessThan(230);
    for (let frame = 0; frame < 25; frame++) world = advanceFlight(world, 0.02);
    expect(world.velocity).toBeGreaterThan(0);
  });
  // Inputs: the values in this case. Expected: allows the olive through a gap but ends on a branch.
  it("allows the olive through a gap but ends on a branch", () => {
    const open = {
      ...newFlight(),
      y: 230,
      branches: [{ x: 85, gapY: 230, gap: FLIGHT.gap, passed: false }],
    };
    expect(advanceFlight(open, 0.01).over).toBe(false);
    expect(advanceFlight({ ...open, y: 90 }, 0.01).over).toBe(true);
  });
  // Inputs: the values in this case. Expected: scores each cleared branch once and replenishes obstacles.
  it("scores each cleared branch once and replenishes obstacles", () => {
    const world = advanceFlight(
      {
        ...newFlight(),
        branches: [{ x: 20, gapY: 230, gap: FLIGHT.gap, passed: false }],
      },
      0.01,
      () => 0.5,
    );
    expect(world.score).toBe(1);
    expect(world.branches).toHaveLength(2);
    expect(advanceFlight(world, 0.01).score).toBe(1);
  });
  // Inputs: the values in this case. Expected: stops at the floor or ceiling and keeps finished rounds frozen.
  it("stops at the floor or ceiling and keeps finished rounds frozen", () => {
    const floor = advanceFlight({ ...newFlight(), y: 470 }, 0.02);
    expect(floor.over).toBe(true);
    expect(advanceFlight(floor, 0.02)).toBe(floor);
    expect(advanceFlight({ ...newFlight(), y: 10 }, 0.02).over).toBe(true);
  });
  // Inputs: the values in this case. Expected: caps frame jumps so switching back to the tab cannot skip obstacles.
  it("caps frame jumps so switching back to the tab cannot skip obstacles", () => {
    expect(advanceFlight(newFlight(), 30)).toEqual(
      advanceFlight(newFlight(), 0.032),
    );
  });
  // Inputs: NaN, infinity, negative scores, invalid time values, and wrong runtime types. Expected: safe defaults keep every number finite.
  it("keeps invalid and overflowing runtime values out of flight state", () => {
    expect(flightSpeedMultiplier(Number.NaN)).toBe(1);
    expect(flightSpeedMultiplier(Number.POSITIVE_INFINITY)).toBe(1);
    expect(flightSpeedMultiplier(-50)).toBe(1);
    expect(flightGap(Number.NaN)).toBe(FLIGHT.gap);
    expect(newFlight(Number.POSITIVE_INFINITY).branches).toHaveLength(2);
    for (const seconds of [Number.NaN, Number.POSITIVE_INFINITY, "fast"] as unknown[]) {
      const result = advanceFlight(newFlight(), seconds as number);
      expect(Number.isFinite(result.y)).toBe(true);
      expect(Number.isFinite(result.velocity)).toBe(true);
    }
  });
});
