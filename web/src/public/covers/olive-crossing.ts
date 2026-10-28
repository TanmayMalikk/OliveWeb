// Cover users: calculates lanes, traffic, and collisions for the crossing game.
export const CROSSING = {
  columns: 18,
  rows: 8,
  cell: 50,
  playerHalfWidth: 0.3,
  vehicleInset: 0.06,
};

export type CrossingDirection = "up" | "down" | "left" | "right";

export interface CrossingVehicle {
  id: number;
  x: number;
  width: number;
  kind: "truck" | "tractor" | "train";
  speed: number;
}

export interface CrossingLane {
  id: number;
  type: "grass" | "road" | "rail" | "water";
  direction: 1 | -1;
  vehicles: CrossingVehicle[];
  pads: number[];
  spawnIn: number;
  spawnCount: number;
}

export interface CrossingWorld {
  lanes: CrossingLane[];
  playerColumn: number;
  playerRow: number;
  position: number;
  score: number;
  nextLaneId: number;
  over: boolean;
}

const wrap = (value: number, length: number) =>
  ((value % length) + length) % length;

// Creates crossing lane.
export function createCrossingLane(
  id: number,
  random: () => number = Math.random,
  forceGrass = false,
  forcedType?: CrossingLane["type"],
): CrossingLane {
  const roll = random();
  const type: CrossingLane["type"] = forceGrass
    ? "grass"
    : forcedType
      ? forcedType
      : roll < 6 / 15
        ? "road"
        : roll < 10 / 15
          ? "grass"
          : roll < 13 / 15
            ? "water"
            : "rail";
  const direction: 1 | -1 = random() < 0.5 ? 1 : -1;
  if (type === "grass")
    return {
      id,
      type,
      direction,
      vehicles: [],
      pads: [],
      spawnIn: Number.POSITIVE_INFINITY,
      spawnCount: 0,
    };
  if (type === "water") {
    const padColumns = new Set([
      Math.floor(CROSSING.columns / 3),
      Math.floor((CROSSING.columns * 2) / 3),
    ]);
    let column = Math.floor(random() * 2);
    while (column < CROSSING.columns) {
      padColumns.add(column);
      column += 2 + Math.floor(random() * 2);
    }
    const pads = [...padColumns].sort((a, b) => a - b);
    return {
      id,
      type,
      direction,
      vehicles: [],
      pads,
      spawnIn: Number.POSITIVE_INFINITY,
      spawnCount: 0,
    };
  }
  if (type === "rail")
    return {
      id,
      type,
      direction,
      vehicles: [],
      pads: [],
      spawnIn: 2.4 + random() * 2.4,
      spawnCount: 0,
    };
  const vehicleCount = 2 + Math.floor(random() * 3);
  let cursor = random() * 3 - 1;
  const vehicles = Array.from({ length: vehicleCount }, (_, index) => {
    const gap = 2.2 + random() * 4;
    const kind = random() < 0.45 ? "truck" : "tractor";
    const vehicle: CrossingVehicle = {
      id: id * 1000 + index,
      x: wrap(cursor, CROSSING.columns + 2) - 1,
      width: kind === "truck" ? 1.15 : 0.9,
      kind,
      speed: 0.65 + random() * 1.25,
    };
    cursor += gap;
    return vehicle;
  });
  return {
    id,
    type,
    direction,
    vehicles,
    pads: [],
    spawnIn: 0.75 + random() * 2.5,
    spawnCount: vehicleCount,
  };
}

export function newCrossing(random: () => number = Math.random): CrossingWorld {
  const lanes = Array.from({ length: CROSSING.rows }, (_, row) => {
    const id = CROSSING.rows - 1 - row;
    return createCrossingLane(id, random, id < 3);
  });
  return {
    lanes,
    playerColumn: Math.floor(CROSSING.columns / 2),
    playerRow: CROSSING.rows - 2,
    position: 0,
    score: 0,
    nextLaneId: CROSSING.rows,
    over: false,
  };
}

export function crossingCollision(world: CrossingWorld) {
  const lane = world.lanes[world.playerRow];
  if (!lane || lane.type === "grass") return false;
  const playerLeft = world.playerColumn + 0.5 - CROSSING.playerHalfWidth;
  const playerRight = world.playerColumn + 0.5 + CROSSING.playerHalfWidth;
  if (lane.type === "water") {
    const onPad = lane.pads.some(
      (column) => Math.abs(world.playerColumn - column) <= 0.52,
    );
    return !onPad;
  }
  return lane.vehicles.some((vehicle) => {
    const vehicleLeft = vehicle.x + CROSSING.vehicleInset;
    const vehicleRight = vehicle.x + vehicle.width - CROSSING.vehicleInset;
    return playerRight > vehicleLeft && playerLeft < vehicleRight;
  });
}

export function railSignal(lane: CrossingLane): "green" | "yellow" | "red" {
  if (lane.type !== "rail") return "green";
  if (lane.vehicles.length || lane.spawnIn <= 0.85) return "red";
  if (lane.spawnIn <= 1.8) return "yellow";
  return "green";
}

export function moveCrossing(
  world: CrossingWorld,
  direction: CrossingDirection,
  random: () => number = Math.random,
): CrossingWorld {
  if (world.over) return world;
  let playerColumn = world.playerColumn;
  let playerRow = world.playerRow;
  let position = world.position;
  let lanes = world.lanes;
  let nextLaneId = world.nextLaneId;

  if (direction === "left") playerColumn = Math.max(0, playerColumn - 1);
  if (direction === "right")
    playerColumn = Math.min(CROSSING.columns - 1, playerColumn + 1);
  if (direction === "up") {
    playerRow = Math.max(0, playerRow - 1);
    position += 1;
    if (playerRow <= 2) {
      lanes = [createCrossingLane(nextLaneId, random), ...lanes.slice(0, -1)];
      nextLaneId += 1;
      playerRow += 1;
    }
  }
  if (direction === "down") {
    playerRow = Math.min(CROSSING.rows - 1, playerRow + 1);
    position = Math.max(0, position - 1);
  }

  const next: CrossingWorld = {
    ...world,
    lanes,
    playerColumn,
    playerRow,
    position,
    score: Math.max(world.score, position),
    nextLaneId,
  };
  return { ...next, over: crossingCollision(next) };
}

export function advanceCrossing(
  world: CrossingWorld,
  seconds: number,
  random: () => number = Math.random,
) {
  if (world.over || seconds <= 0) return world;
  const delta = Math.min(seconds, 0.05);
  const lanes = world.lanes.map((lane) => {
    if (lane.type === "grass" || lane.type === "water") return lane;
    let vehicles = lane.vehicles
      .map((vehicle) => ({
        ...vehicle,
        x: vehicle.x + lane.direction * vehicle.speed * delta,
      }))
      .filter((vehicle) =>
        lane.direction === 1
          ? vehicle.x < CROSSING.columns + 1.5
          : vehicle.x + vehicle.width > -1.5,
      );
    let spawnIn = lane.spawnIn - delta;
    let spawnCount = lane.spawnCount;
    if (spawnIn <= 0) {
      const kind: CrossingVehicle["kind"] =
        lane.type === "rail" ? "train" : random() < 0.45 ? "truck" : "tractor";
      const width =
        kind === "train" ? 3.2 + random() * 1.5 : kind === "truck" ? 1.15 : 0.9;
      const entryClear = vehicles.every((vehicle) =>
        lane.direction === 1
          ? vehicle.x > 1.3
          : vehicle.x + vehicle.width < CROSSING.columns - 0.3,
      );
      if (entryClear) {
        vehicles = [
          ...vehicles,
          {
            id: lane.id * 1000 + spawnCount,
            x: lane.direction === 1 ? -width - 0.15 : CROSSING.columns + 0.15,
            width,
            kind,
            speed:
              kind === "train" ? 12 + random() * 8 : 0.65 + random() * 1.25,
          },
        ];
        spawnCount += 1;
        spawnIn =
          lane.type === "rail" ? 3 + random() * 4 : 0.85 + random() * 2.8;
      } else spawnIn = 0.2 + random() * 0.25;
    }
    return { ...lane, vehicles, spawnIn, spawnCount };
  });
  const next = { ...world, lanes };
  return { ...next, over: crossingCollision(next) };
}
