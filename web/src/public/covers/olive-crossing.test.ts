// Tests traffic and collision rules for cover users.
import { describe, expect, it } from "vitest";
import {
  CROSSING,
  advanceCrossing,
  crossingCollision,
  createCrossingLane,
  moveCrossing,
  newCrossing,
  railSignal,
  type CrossingDirection,
  type CrossingWorld,
} from "./olive-crossing";
import { computePlayerPose } from "./OliveCrossingBoard";

describe("Olive Crossing", () => {
  // Inputs: the values in this case. Expected: starts the olive on a safe lane.
  it("starts the olive on a safe lane", () => {
    const world = newCrossing(() => 0);
    expect(world.lanes).toHaveLength(13);
    expect(world.lanes.length - world.playerRow).toBe(4);
    expect(world.lanes[world.playerRow].type).toBe("grass");
    expect(world.score).toBe(0);
  });

  // Inputs: the values in this case. Expected: chooses road, grass, water, and rail lanes with 6:4:3:2 weights.
  it("chooses road, grass, water, and rail lanes with 6:4:3:2 weights", () => {
    // Checks the lane type step with the test's sample data.
    const laneType = (roll: number) => createCrossingLane(12, () => roll).type;
    expect(laneType(0)).toBe("road");
    expect(laneType(0.399)).toBe("road");
    expect(laneType(0.4)).toBe("grass");
    expect(laneType(0.665)).toBe("grass");
    expect(laneType(0.67)).toBe("water");
    expect(laneType(0.865)).toBe("water");
    expect(laneType(0.87)).toBe("rail");
    expect(laneType(0.999)).toBe("rail");
  });

  // Inputs: the values in this case. Expected: keeps side-to-side movement within the grove.
  it("keeps side-to-side movement within the grove", () => {
    let world = newCrossing(() => 0);
    for (let count = 0; count < CROSSING.columns * 2; count++)
      world = moveCrossing(world, "left");
    expect(world.playerColumn).toBe(0);
    for (let count = 0; count < CROSSING.columns * 2; count++)
      world = moveCrossing(world, "right");
    expect(world.playerColumn).toBe(CROSSING.columns - 1);
  });

  // Inputs: the values in this case. Expected: scores only new forward progress and grows the road.
  it("scores only new forward progress and grows the road", () => {
    let world = newCrossing(() => 0);
    world = {
      ...world,
      lanes: world.lanes.map((lane) => ({
        ...lane,
        type: "grass" as const,
        vehicles: [],
        pads: [],
      })),
    };
    world = moveCrossing(world, "up", () => 0);
    world = moveCrossing(world, "down", () => 0);
    world = moveCrossing(world, "up", () => 0);
    expect(world.score).toBe(1);
    for (let count = 0; count < 5; count++)
      world = moveCrossing(world, "up", () => 0);
    expect(world.nextLaneId).toBeGreaterThan(CROSSING.rows);
    expect(world.lanes).toHaveLength(CROSSING.rows);
    expect(world.lanes.length - world.playerRow).toBe(5);
    const back = moveCrossing(world, "down", () => 0);
    expect(back.lanes.length - back.playerRow).toBe(4);
    expect(moveCrossing(back, "down", () => 0)).toBe(back);
  });

  // Inputs: the values in this case. Expected: moves farm traffic and detects a collision.
  it("moves farm traffic and detects a collision", () => {
    const base = newCrossing(() => 0);
    const road = createCrossingLane(30, () => 0.9, false, "road");
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) =>
        row === base.playerRow
          ? {
              ...road,
              vehicles: [
                {
                  id: 1,
                  x: base.playerColumn + 0.45,
                  width: 1,
                  kind: "tractor",
                  speed: 1,
                },
              ],
            }
          : lane,
      ),
    };
    const next = advanceCrossing(world, 0.01);
    expect(next.lanes[next.playerRow].vehicles[0].x).not.toBe(
      world.lanes[world.playerRow].vehicles[0].x,
    );
    expect(next.over).toBe(true);
  });

  // Inputs: the values in this case. Expected: uses the visible edges of the olive and cart as hitboxes.
  it("uses the visible edges of the olive and cart as hitboxes", () => {
    const base = newCrossing(() => 0);
    const playerLeft = base.playerColumn + 0.5 - CROSSING.playerHalfWidth;
    const withVehicleAt = (x: number): CrossingWorld => ({
      ...base,
      lanes: base.lanes.map((lane, row) =>
        row === base.playerRow
          ? {
              ...lane,
              type: "road",
              vehicles: [{ id: 1, x, width: 1, kind: "tractor", speed: 1 }],
            }
          : lane,
      ),
    });
    expect(
      crossingCollision(withVehicleAt(playerLeft - 1 + CROSSING.vehicleInset)),
    ).toBe(false);
    expect(
      crossingCollision(
        withVehicleAt(playerLeft - 1 + CROSSING.vehicleInset + 0.02),
      ),
    ).toBe(true);
  });

  // Inputs: the values in this case. Expected: gives traffic varied gaps, speeds and arrival times.
  it("gives traffic varied gaps, speeds and arrival times", () => {
    const values = [
      0.9, 0.2, 0.8, 0.1, 0.1, 0.2, 0.3, 0.8, 0.4, 0.6, 0.7, 0.2, 0.9, 0.5, 0.4,
      0.7, 0.1,
    ];
    let call = 0;
    const lane = createCrossingLane(
      40,
      () => values[call++ % values.length],
      false,
      "road",
    );
    expect(lane.type).toBe("road");
    expect(
      new Set(lane.vehicles.map((vehicle) => vehicle.speed)).size,
    ).toBeGreaterThan(1);
    const positions = lane.vehicles
      .map((vehicle) => vehicle.x)
      .sort((a, b) => a - b);
    const gaps = positions
      .slice(1)
      .map((position, index) =>
        Number((position - positions[index]).toFixed(2)),
      );
    expect(new Set(gaps).size).toBeGreaterThan(1);
    expect(lane.spawnIn).toBeGreaterThanOrEqual(0.75);
    expect(lane.spawnIn).toBeLessThanOrEqual(3.25);
  });

  // Inputs: the values in this case. Expected: spawns new carts individually after each lane's wait.
  it("spawns new carts individually after each lane's wait", () => {
    const base = newCrossing(() => 0);
    const road = {
      ...createCrossingLane(50, () => 0.9, false, "road"),
      vehicles: [],
      spawnIn: 0,
      spawnCount: 4,
    };
    const world = {
      ...base,
      lanes: base.lanes.map((lane, row) => (row === 0 ? road : lane)),
    };
    const next = advanceCrossing(world, 0.01, () => 0.5);
    expect(next.lanes[0].vehicles).toHaveLength(1);
    expect(next.lanes[0].spawnIn).toBeGreaterThan(0.85);
    expect(next.lanes[0].spawnCount).toBe(5);
  });

  // Inputs: the values in this case. Expected: keeps the olive safe only when it lands on a lily pad.
  it("keeps the olive safe only when it lands on a lily pad", () => {
    const base = newCrossing(() => 0);
    const water = {
      ...createCrossingLane(60, () => 0.3, false, "water"),
      pads: [base.playerColumn],
    };
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) =>
        row === base.playerRow ? water : lane,
      ),
    };
    expect(crossingCollision(world)).toBe(false);
    expect(moveCrossing(world, "right").over).toBe(true);
  });

  // Inputs: the values in this case. Expected: keeps two aligned lily-pad routes through every water lane.
  it("keeps two aligned lily-pad routes through every water lane", () => {
    const leftRoute = Math.floor(CROSSING.columns / 3);
    const rightRoute = Math.floor((CROSSING.columns * 2) / 3);
    for (const roll of [0, 0.2, 0.5, 0.8, 0.999]) {
      const water = createCrossingLane(65, () => roll, false, "water");
      expect(water.pads).toContain(leftRoute);
      expect(water.pads).toContain(rightRoute);
      expect(water.pads.length).toBeGreaterThanOrEqual(2);
    }
  });

  // Inputs: the values in this case. Expected: releases fast trains onto railway lanes.
  it("releases fast trains onto railway lanes", () => {
    const base = newCrossing(() => 0);
    const rail = {
      ...createCrossingLane(70, () => 0.45, false, "rail"),
      spawnIn: 0,
    };
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) => (row === 0 ? rail : lane)),
    };
    const next = advanceCrossing(world, 0.01, () => 0.5);
    expect(next.lanes[0].vehicles).toHaveLength(1);
    expect(next.lanes[0].vehicles[0].kind).toBe("train");
    expect(next.lanes[0].vehicles[0].speed).toBeGreaterThan(11);
    expect(next.lanes[0].spawnIn).toBeGreaterThan(2);
  });

  // Inputs: the values in this case. Expected: changes rail signals from green to yellow to red before a train.
  it("changes rail signals from green to yellow to red before a train", () => {
    const rail = createCrossingLane(75, () => 0.5, false, "rail");
    expect(railSignal({ ...rail, spawnIn: 3 })).toBe("green");
    expect(railSignal({ ...rail, spawnIn: 1.4 })).toBe("yellow");
    expect(railSignal({ ...rail, spawnIn: 0.5 })).toBe("red");
    expect(
      railSignal({
        ...rail,
        spawnIn: 4,
        vehicles: [{ id: 1, x: 1, width: 4, speed: 3, kind: "train" }],
      }),
    ).toBe("red");
  });

  // Inputs: the values in this case. Expected: freezes the world after the round ends.
  it("freezes the world after the round ends", () => {
    const world = { ...newCrossing(() => 0), over: true };
    expect(moveCrossing(world, "up")).toBe(world);
    expect(advanceCrossing(world, 1)).toBe(world);
  });
  // Inputs: NaN, infinity, strings, and an unknown direction at the JavaScript boundary. Expected: the crossing state stays valid and unchanged.
  it("ignores wrong runtime types and non-finite frame times", () => {
    const world = newCrossing(() => Number.NaN);
    expect(world.lanes.every((lane) => Number.isFinite(lane.id))).toBe(true);
    expect(moveCrossing(world, "sideways" as never)).toBe(world);
    for (const seconds of [Number.NaN, Number.POSITIVE_INFINITY, "fast"] as unknown[]) {
      expect(advanceCrossing(world, seconds as number)).toBe(world);
    }
  });

  // Inputs: grass obstacles in front of the player. Expected: prevents hopping onto trees/boulders.
  it("blocks hops into grass trees and boulders", () => {
    const base = newCrossing(() => 0);
    const blockedCol = base.playerColumn;
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) =>
        row === base.playerRow - 1
          ? { ...lane, type: "grass", obstacles: [blockedCol], vehicles: [], pads: [] }
          : lane,
      ),
    };
    const next = moveCrossing(world, "up");
    expect(next).toBe(world);
  });

  // Inputs: olive standing on a drifting river log. Expected: log carries olive sideways and snaps column when hopping onto grass.
  it("carries the olive on drifting logs and snaps to grid column when stepping off", () => {
    const base = newCrossing(() => 0);
    const logLane = {
      ...createCrossingLane(15, () => 0.4, false, "water"),
      direction: 1 as const,
      waterStyle: "logs" as const,
      logSpeed: 2,
      logs: [{ id: 1, x: base.playerColumn - 0.5, width: 3 }],
    };
    const safeGrass = {
      ...createCrossingLane(16, () => 0, true),
      obstacles: [],
    };
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) => {
        if (row === base.playerRow) return logLane;
        if (row === base.playerRow - 1) return safeGrass;
        return lane;
      }),
    };
    const drifted = advanceCrossing(world, 0.05);
    expect(drifted.over).toBe(false);
    expect(drifted.playerColumn).toBeGreaterThan(base.playerColumn);

    const steppedOff = moveCrossing(drifted, "up");
    expect(Number.isInteger(steppedOff.playerColumn)).toBe(true);
  });

  // Inputs: coin in next lane cell. Expected: collects coin and marks deathReason when stepping into water.
  it("collects golden coins and records deathReason on water splash", () => {
    const base = newCrossing(() => 0);
    const coinRow = base.playerRow - 1;
    const world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) =>
        row === coinRow
          ? {
              ...lane,
              type: "grass",
              obstacles: [],
              coins: [base.playerColumn],
              vehicles: [],
              pads: [],
            }
          : lane,
      ),
    };
    const collected = moveCrossing(world, "up");
    expect(collected.coinsCollected).toBe(1);
    expect(collected.coinJustCollected).toBe(true);
    expect(collected.lanes[collected.playerRow].coins).toEqual([]);

    const waterAhead: CrossingWorld = {
      ...collected,
      lanes: collected.lanes.map((lane, row) =>
        row === collected.playerRow - 1
          ? {
              ...createCrossingLane(80, () => 0.3, false, "water"),
              pads: [],
            }
          : lane,
      ),
    };
    const splashed = moveCrossing(waterAhead, "up");
    expect(splashed.over).toBe(true);
    expect(splashed.deathReason).toBe("water");
  });

  // Inputs: fast trailing car behind slower truck in the same road lane. Expected: vehicles never overlap or drive through each other.
  it("prevents trailing road vehicles from overlapping slower vehicles ahead", () => {
    const base = newCrossing(() => 0);
    const road = {
      ...createCrossingLane(90, () => 0.2, false, "road"),
      direction: 1 as const,
      vehicles: [
        { id: 1, x: 6.0, width: 1.25, kind: "truck" as const, speed: 0.7 },
        { id: 2, x: 4.0, width: 0.95, kind: "car" as const, speed: 2.0 },
      ],
      spawnIn: 99,
    };
    let world: CrossingWorld = {
      ...base,
      lanes: base.lanes.map((lane, row) => (row === 0 ? road : lane)),
    };
    for (let step = 0; step < 80; step++) {
      world = advanceCrossing(world, 0.05, () => 0.5);
    }
    const [front, trailing] = [...world.lanes[0].vehicles].sort((a, b) => b.x - a.x);
    expect(trailing.x + trailing.width).toBeLessThanOrEqual(front.x - 0.49);
  });

  // Inputs: olive between two drifting river logs. Expected: triggers water collision accurately at log edges.
  it("detects water splash accurately when standing between drifting logs", () => {
    const base = newCrossing(() => 0);
    const logLane = {
      ...createCrossingLane(95, () => 0.4, false, "water"),
      direction: 1 as const,
      waterStyle: "logs" as const,
      logSpeed: 1,
      logs: [
        { id: 1, x: 6, width: 2.5 },
        { id: 2, x: 11.5, width: 2.5 },
      ],
    };
    const world: CrossingWorld = {
      ...base,
      playerColumn: 9.5,
      lanes: base.lanes.map((lane, row) => (row === base.playerRow ? logLane : lane)),
    };
    expect(crossingCollision(world)).toBe(true);
  });

  // Inputs: continuous frames on a floating log plus side hops along the log. Expected: player rides smoothly without freezing or false splash.
  it("keeps the player riding smoothly on a floating log across frames and side hops", () => {
    const base = newCrossing(() => 0);
    const logLane = {
      ...createCrossingLane(15, () => 0.4, false, "water"),
      direction: 1 as const,
      waterStyle: "logs" as const,
      logSpeed: 1.5,
      logs: [{ id: 1, x: 4, width: 4 }],
    };
    let world: CrossingWorld = {
      ...base,
      playerColumn: 5.5,
      lanes: base.lanes.map((lane, row) => (row === base.playerRow ? logLane : lane)),
    };

    const startCol = world.playerColumn;
    for (let frame = 0; frame < 40; frame++) {
      world = advanceCrossing(world, 1 / 60);
      expect(world.over).toBe(false);
    }
    expect(world.playerColumn).toBeCloseTo(startCol + 1.5 * (40 / 60), 2);

    const hoppedRight = moveCrossing(world, "right");
    expect(hoppedRight.over).toBe(false);
    expect(hoppedRight.playerColumn).toBeCloseTo(world.playerColumn + 1, 2);

    const hoppedLeft = moveCrossing(hoppedRight, "left");
    expect(hoppedLeft.over).toBe(false);
    expect(hoppedLeft.playerColumn).toBeCloseTo(world.playerColumn, 2);

    // Hopping directly onto an adjacent drifting-log lane preserves continuous column instead of snapping.
    const nextLogLane = {
      ...createCrossingLane(16, () => 0.4, false, "water"),
      direction: -1 as const,
      waterStyle: "logs" as const,
      logSpeed: 1.2,
      logs: [{ id: 2, x: hoppedLeft.playerColumn - 0.8, width: 3 }],
    };
    const twoLogsWorld: CrossingWorld = {
      ...hoppedLeft,
      lanes: hoppedLeft.lanes.map((lane, row) =>
        row === hoppedLeft.playerRow - 1 ? nextLogLane : lane,
      ),
    };
    const hoppedToNextLog = moveCrossing(twoLogsWorld, "up");
    expect(hoppedToNextLog.over).toBe(false);
    expect(hoppedToNextLog.playerColumn).toBeCloseTo(hoppedLeft.playerColumn, 5);
  });

  // Inputs: directional hops in all 4 directions and idle log riding. Expected: produces smooth offsets, 3D jump arc, directional lean, stride, and settling.
  it("computes directional hop poses for up, down, left, right and gentle log bobbing", () => {
    const idleRiding = computePlayerPose(null, 0, true, 0.35);
    expect(idleRiding.dx).toBe(0);
    expect(idleRiding.dy).toBe(0);
    expect(idleRiding.jumpZ).toBe(0);
    expect(idleRiding.bobZ).not.toBe(0);

    const cases: Array<{
      direction: CrossingDirection;
      offsetX: number;
      offsetY: number;
      expectLeanX: (v: number) => boolean;
      expectLeanY: (v: number) => boolean;
    }> = [
      { direction: "up", offsetX: 0, offsetY: 1, expectLeanX: (v) => v === 0, expectLeanY: (v) => v < 0 },
      { direction: "down", offsetX: 0, offsetY: -1, expectLeanX: (v) => v === 0, expectLeanY: (v) => v > 0 },
      { direction: "left", offsetX: 1, offsetY: 0, expectLeanX: (v) => v < 0, expectLeanY: (v) => v === 0 },
      { direction: "right", offsetX: -1, offsetY: 0, expectLeanX: (v) => v > 0, expectLeanY: (v) => v === 0 },
    ];

    for (const c of cases) {
      const midEven = computePlayerPose(
        { direction: c.direction, progress: 0.5, offsetX: c.offsetX, offsetY: c.offsetY },
        2,
        false,
        0,
      );
      const midOdd = computePlayerPose(
        { direction: c.direction, progress: 0.5, offsetX: c.offsetX, offsetY: c.offsetY },
        3,
        false,
        0,
      );
      expect(midEven.jumpZ).toBeGreaterThan(15);
      expect(midEven.shadowScale).toBeLessThan(0.8);
      expect(c.expectLeanX(midEven.leanX)).toBe(true);
      expect(c.expectLeanY(midEven.leanY)).toBe(true);
      expect(midEven.stride).toBe(-midOdd.stride);

      const landed = computePlayerPose(
        { direction: c.direction, progress: 1, offsetX: c.offsetX, offsetY: c.offsetY },
        2,
        false,
        0,
      );
      expect(landed.dx).toBe(0);
      expect(landed.dy).toBe(0);
      expect(landed.jumpZ).toBeCloseTo(0, 5);
    }
  });
});
