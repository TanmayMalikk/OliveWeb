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
  it("caps speed at 7.5x starting at 325 points", () => {
    expect(flightSpeedMultiplier(324)).toBe(7.4);
    for (const score of [325, 330, 10000]) {
      expect(flightSpeedMultiplier(score)).toBe(7.5);
      const world = { ...newFlight(), score };
      const next = advanceFlight(world, 0.01);
      expect(world.branches[0].x - next.branches[0].x).toBeCloseTo(
        FLIGHT.speed * 7.5 * 0.01,
      );
    }
  });
  it("narrows the opening every ten points and stops at two olive bodies", () => {
    expect(flightGap(0)).toBe(174);
    expect(flightGap(9)).toBe(174);
    expect(flightGap(10)).toBe(166);
    expect(flightGap(19)).toBe(166);
    expect(flightGap(20)).toBe(158);
    expect(flightGap(110)).toBe(flightGap(10000));
    expect(flightGap(10000) - FLIGHT.branchStroke).toBe(FLIGHT.radiusY * 4);
  });
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
  it("increases horizontal speed by 0.1x at each five-point milestone", () => {
    for (const [score, multiplier] of [
      [0, 1],
      [4, 1],
      [5, 1.1],
      [9, 1.1],
      [10, 1.2],
      [15, 1.3],
      [50, 2],
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
  it("applies a newly earned milestone on the next frame and resets on a new round", () => {
    const milestone = advanceFlight(
      {
        ...newFlight(),
        score: 4,
        branches: [
          { x: 20, gapY: 230, gap: FLIGHT.gap, passed: false },
          { x: 350, gapY: 238, gap: FLIGHT.gap, passed: false },
        ],
      },
      0.01,
    );
    expect(milestone.score).toBe(5);
    const next = advanceFlight(milestone, 0.01);
    expect(milestone.branches[1].x - next.branches[1].x).toBeCloseTo(
      FLIGHT.speed * 1.1 * 0.01,
    );
    expect(flightSpeedMultiplier(newFlight().score)).toBe(1);
  });
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
  it("does not collide with empty space beside the narrow branch tip", () => {
    expect(hitsLeafyBranch(95, 230, false, 230, 0)).toBe(false);
    expect(hitsLeafyBranch(95, 230, true, 230, 0)).toBe(false);
    expect(hitsLeafyBranch(80, 230, false, 230, 0)).toBe(true);
    expect(hitsLeafyBranch(80, 230, true, 230, 0)).toBe(true);
  });
  it("detects visible leaves without filling the empty space between them", () => {
    expect(hitsLeafyBranch(50, 200, false, 245, 0)).toBe(true);
    expect(hitsLeafyBranch(50, 200, false, 210, 0)).toBe(false);
    expect(hitsLeafyBranch(50, 280, true, 235, 0)).toBe(true);
    expect(hitsLeafyBranch(50, 280, true, 270, 0)).toBe(false);
  });
  it("flaps upward then falls under gravity", () => {
    let world = { ...newFlight(), velocity: FLIGHT.lift };
    world = advanceFlight(world, 0.02);
    expect(world.y).toBeLessThan(230);
    for (let frame = 0; frame < 25; frame++) world = advanceFlight(world, 0.02);
    expect(world.velocity).toBeGreaterThan(0);
  });
  it("allows the olive through a gap but ends on a branch", () => {
    const open = {
      ...newFlight(),
      y: 230,
      branches: [{ x: 85, gapY: 230, gap: FLIGHT.gap, passed: false }],
    };
    expect(advanceFlight(open, 0.01).over).toBe(false);
    expect(advanceFlight({ ...open, y: 90 }, 0.01).over).toBe(true);
  });
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
  it("stops at the floor or ceiling and keeps finished rounds frozen", () => {
    const floor = advanceFlight({ ...newFlight(), y: 470 }, 0.02);
    expect(floor.over).toBe(true);
    expect(advanceFlight(floor, 0.02)).toBe(floor);
    expect(advanceFlight({ ...newFlight(), y: 10 }, 0.02).over).toBe(true);
  });
  it("caps frame jumps so switching back to the tab cannot skip obstacles", () => {
    expect(advanceFlight(newFlight(), 30)).toEqual(
      advanceFlight(newFlight(), 0.032),
    );
  });
});
