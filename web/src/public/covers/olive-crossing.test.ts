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
  type CrossingWorld,
} from "./olive-crossing";

describe("Olive Crossing", () => {
  it("starts the olive on a safe lane", () => {
    const world = newCrossing(() => 0);
    expect(world.lanes[world.playerRow].type).toBe("grass");
    expect(world.score).toBe(0);
  });

  it("chooses road, grass, water, and rail lanes with 6:4:3:2 weights", () => {
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

  it("keeps side-to-side movement within the grove", () => {
    let world = newCrossing(() => 0);
    for (let count = 0; count < CROSSING.columns * 2; count++)
      world = moveCrossing(world, "left");
    expect(world.playerColumn).toBe(0);
    for (let count = 0; count < CROSSING.columns * 2; count++)
      world = moveCrossing(world, "right");
    expect(world.playerColumn).toBe(CROSSING.columns - 1);
  });

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
  });

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

  it("freezes the world after the round ends", () => {
    const world = { ...newCrossing(() => 0), over: true };
    expect(moveCrossing(world, "up")).toBe(world);
    expect(advanceCrossing(world, 1)).toBe(world);
  });
});
