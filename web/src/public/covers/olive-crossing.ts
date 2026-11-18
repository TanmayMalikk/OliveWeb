// Cover users: calculates lanes, traffic, obstacles, moving logs, coins, and collisions for Olive Crossing.
export const CROSSING = {
  columns: 18,
  rows: 8,
  cell: 50,
  playerHalfWidth: 0.3,
  vehicleInset: 0.06,
};

export type CrossingDirection = "up" | "down" | "left" | "right";
export type CrossingVehicleKind = "car" | "tractor" | "truck" | "semi" | "train";
export type CrossingDeathReason = "traffic" | "train" | "water" | "eagle";

export interface CrossingVehicle {
  id: number;
  x: number;
  width: number;
  kind: CrossingVehicleKind;
  speed: number;
  colorVariant?: number;
}

export interface CrossingLog {
  id: number;
  x: number;
  width: number;
}

export interface CrossingLane {
  id: number;
  type: "grass" | "road" | "rail" | "water";
  direction: 1 | -1;
  vehicles: CrossingVehicle[];
  pads: number[];
  waterStyle?: "lilypads" | "logs";
  logs?: CrossingLog[];
  logSpeed?: number;
  obstacles?: number[];
  coins?: number[];
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
  coinsCollected: number;
  coinJustCollected?: boolean;
  deathReason?: CrossingDeathReason;
}

const SAFE_CORRIDORS = new Set([
  Math.floor(CROSSING.columns / 3),
  Math.floor(CROSSING.columns / 2),
  Math.floor((CROSSING.columns * 2) / 3),
]);

// Keeps moving objects inside the board when they pass an edge.
const wrap = (value: number, length: number) =>
  ((value % length) + length) % length;

// Replaces an invalid random value so lane generation always stays within range.
const validRandom = (random: () => number) => {
  const value = random();
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0.5;
};

const vehicleWidth = (kind: CrossingVehicleKind, random: () => number) => {
  if (kind === "train") return 3.4 + validRandom(random) * 1.6;
  if (kind === "semi") return 1.85;
  if (kind === "truck") return 1.25;
  if (kind === "car") return 0.95;
  return 0.9;
};

const pickRoadKind = (roll: number): CrossingVehicleKind => {
  if (roll < 0.34) return "car";
  if (roll < 0.62) return "tractor";
  if (roll < 0.85) return "truck";
  return "semi";
};

// Creates one grass, road, water, or rail lane with Crossy Road spacing, obstacles, logs, and coins.
export function createCrossingLane(
  id: number,
  random: () => number = Math.random,
  forceGrass = false,
  forcedType?: CrossingLane["type"],
): CrossingLane {
  const roll = validRandom(random);
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
  const direction: 1 | -1 = validRandom(random) < 0.5 ? 1 : -1;

  if (type === "grass") {
    const obstacles: number[] = [];
    const coins: number[] = [];
    if (!forceGrass && id >= 3) {
      // Decorative Crossy Road edge treeline plus scattered grove boulders/trees outside safe corridors.
      for (let col = 0; col < CROSSING.columns; col++) {
        const isEdge = col === 0 || col === CROSSING.columns - 1;
        if (SAFE_CORRIDORS.has(col)) continue;
        if (isEdge && (id + col) % 2 === 0) {
          obstacles.push(col);
          continue;
        }
        const treeHash = ((id * 37 + col * 73) % 19) / 19;
        if (treeHash < 0.24) {
          obstacles.push(col);
        }
      }
      if (id % 3 === 1) {
        const coinCol = 3 + ((id * 5) % (CROSSING.columns - 6));
        if (!obstacles.includes(coinCol)) coins.push(coinCol);
      }
    }
    return {
      id,
      type,
      direction,
      vehicles: [],
      pads: [],
      obstacles,
      coins,
      spawnIn: Number.POSITIVE_INFINITY,
      spawnCount: 0,
    };
  }

  if (type === "water") {
    const padColumns = new Set([
      Math.floor(CROSSING.columns / 3),
      Math.floor((CROSSING.columns * 2) / 3),
    ]);
    let column = Math.floor(validRandom(random) * 2);
    while (column < CROSSING.columns) {
      padColumns.add(column);
      column += 2 + Math.floor(validRandom(random) * 2);
    }
    const pads = [...padColumns].sort((a, b) => a - b);

    // Odd river lanes >= 5 flow with drifting wooden Crossy Road logs while still preserving pad routes.
    const useLogs = id >= 5 && id % 2 === 1;
    const logs: CrossingLog[] = [];
    let logSpeed = 0;
    if (useLogs) {
      logSpeed = 0.85 + validRandom(random) * 1.05;
      const logCount = 6;
      const trackSpan = CROSSING.columns + 14;
      const step = trackSpan / logCount;
      let cursor = -7 + validRandom(random) * 1.5;
      for (let idx = 0; idx < logCount; idx++) {
        const width = 2.1 + ((id + idx) % 3) * 0.45;
        logs.push({
          id: id * 100 + idx,
          x: wrap(cursor + 7, trackSpan) - 7,
          width,
        });
        cursor += step;
      }
    }

    const coins =
      !useLogs && id % 4 === 2 && pads.length > 0
        ? [pads[Math.floor(pads.length / 2)]]
        : [];

    return {
      id,
      type,
      direction,
      vehicles: [],
      pads,
      waterStyle: useLogs ? "logs" : "lilypads",
      logs,
      logSpeed,
      coins,
      spawnIn: Number.POSITIVE_INFINITY,
      spawnCount: 0,
    };
  }

  if (type === "rail") {
    return {
      id,
      type,
      direction,
      vehicles: [],
      pads: [],
      coins: [],
      spawnIn: 2.4 + validRandom(random) * 2.4,
      spawnCount: 0,
    };
  }

  const targetCount = 3 + Math.floor(validRandom(random) * 3);
  let cursor = -4.5 + validRandom(random) * 2.5;
  const vehicles: CrossingVehicle[] = [];
  for (let index = 0; index < targetCount; index++) {
    const gap = 2.2 + validRandom(random) * 3.4;
    const kind = pickRoadKind(validRandom(random));
    const width = vehicleWidth(kind, () => 0.5);
    if (cursor + width > CROSSING.columns + 4.5) break;
    vehicles.push({
      id: id * 1000 + index,
      x: cursor,
      width,
      kind,
      speed: 0.7 + validRandom(random) * 1.35,
      colorVariant: (id + index) % 4,
    });
    cursor += width + gap;
  }

  const coins = id % 5 === 2 ? [Math.floor(CROSSING.columns / 2) + (id % 3) - 1] : [];

  return {
    id,
    type,
    direction,
    vehicles,
    pads: [],
    coins,
    spawnIn: 0.75 + validRandom(random) * 2.5,
    spawnCount: vehicles.length,
  };
}

// Starts a new crossing board with the olive in the safe area.
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
    coinsCollected: 0,
    coinJustCollected: false,
  };
}

// Reports whether the olive currently touches traffic, water, or a train.
export function crossingCollision(world: CrossingWorld) {
  const lane = world.lanes[world.playerRow];
  if (!lane || lane.type === "grass") return false;
  const playerLeft = world.playerColumn + 0.5 - CROSSING.playerHalfWidth;
  const playerRight = world.playerColumn + 0.5 + CROSSING.playerHalfWidth;

  if (lane.type === "water") {
    if (lane.waterStyle === "logs" && lane.logs && lane.logs.length > 0) {
      if (
        world.playerColumn < -0.45 ||
        world.playerColumn > CROSSING.columns - 0.55
      ) {
        return true;
      }
      const playerCenter = world.playerColumn + 0.5;
      const onLog = lane.logs.some(
        (log) =>
          playerCenter >= log.x - 0.04 &&
          playerCenter <= log.x + log.width + 0.04,
      );
      return !onLog;
    }
    const onPad = lane.pads.some(
      (column) => Math.abs(world.playerColumn - column) <= 0.42,
    );
    return !onPad;
  }

  return lane.vehicles.some((vehicle) => {
    const vehicleLeft = vehicle.x + CROSSING.vehicleInset;
    const vehicleRight = vehicle.x + vehicle.width - CROSSING.vehicleInset;
    return playerRight > vehicleLeft && playerLeft < vehicleRight;
  });
}

function detectDeathReason(world: CrossingWorld): CrossingDeathReason | undefined {
  const lane = world.lanes[world.playerRow];
  if (!lane) return undefined;
  if (lane.type === "water") return "water";
  if (lane.type === "rail") return "train";
  return "traffic";
}

// Changes the rail light as a train gets closer to the crossing.
export function railSignal(lane: CrossingLane): "green" | "yellow" | "red" {
  if (lane.type !== "rail") return "green";
  if (lane.vehicles.length || lane.spawnIn <= 0.85) return "red";
  if (lane.spawnIn <= 1.8) return "yellow";
  return "green";
}

// Moves the olive one space when the destination is open inside the board.
export function moveCrossing(
  world: CrossingWorld,
  direction: CrossingDirection,
  random: () => number = Math.random,
): CrossingWorld {
  if (world.over) return world;
  if (!["up", "down", "left", "right"].includes(direction)) return world;

  let playerColumn = world.playerColumn;
  let playerRow = world.playerRow;
  let position = world.position;
  let lanes = world.lanes;
  let nextLaneId = world.nextLaneId;

  const currentLane = lanes[playerRow];
  const onMovingLog =
    currentLane?.type === "water" &&
    currentLane.waterStyle === "logs" &&
    (currentLane.logs?.length ?? 0) > 0;

  if (direction === "left") {
    const candidateCol = Math.max(0, playerColumn - 1);
    const checkCol = Math.round(candidateCol);
    if (
      currentLane?.type === "grass" &&
      currentLane.obstacles?.includes(checkCol)
    ) {
      return world;
    }
    playerColumn = candidateCol;
  }

  if (direction === "right") {
    const candidateCol = Math.min(CROSSING.columns - 1, playerColumn + 1);
    const checkCol = Math.round(candidateCol);
    if (
      currentLane?.type === "grass" &&
      currentLane.obstacles?.includes(checkCol)
    ) {
      return world;
    }
    playerColumn = candidateCol;
  }

  if (direction === "up") {
    const targetRow = Math.max(0, playerRow - 1);
    const snappedCol = Math.max(
      0,
      Math.min(CROSSING.columns - 1, Math.round(playerColumn)),
    );
    const targetLane = lanes[targetRow];
    if (
      targetLane?.type === "grass" &&
      targetLane.obstacles?.includes(snappedCol)
    ) {
      return world;
    }

    const destinationUsesLogs =
      targetLane?.type === "water" && targetLane.waterStyle === "logs";
    if (onMovingLog || !destinationUsesLogs) {
      playerColumn = snappedCol;
    }

    playerRow = targetRow;
    position += 1;
    if (playerRow <= 2) {
      lanes = [createCrossingLane(nextLaneId, random), ...lanes.slice(0, -1)];
      nextLaneId += 1;
      playerRow += 1;
    }
  }

  if (direction === "down") {
    if (playerRow >= CROSSING.rows - 1) return world;
    const targetRow = playerRow + 1;
    const snappedCol = Math.max(
      0,
      Math.min(CROSSING.columns - 1, Math.round(playerColumn)),
    );
    const targetLane = lanes[targetRow];
    if (
      targetLane?.type === "grass" &&
      targetLane.obstacles?.includes(snappedCol)
    ) {
      return world;
    }

    const destinationUsesLogs =
      targetLane?.type === "water" && targetLane.waterStyle === "logs";
    if (onMovingLog || !destinationUsesLogs) {
      playerColumn = snappedCol;
    }

    playerRow = targetRow;
    position = Math.max(0, position - 1);
  }

  // Collect golden coin if standing cleanly on one in the target cell.
  let coinsCollected = world.coinsCollected ?? 0;
  let coinJustCollected = false;
  const landedCol = Math.round(playerColumn);
  const landedLane = lanes[playerRow];
  if (landedLane?.coins?.includes(landedCol)) {
    coinsCollected += 1;
    coinJustCollected = true;
    lanes = lanes.map((lane, row) =>
      row === playerRow
        ? { ...lane, coins: (lane.coins ?? []).filter((c) => c !== landedCol) }
        : lane,
    );
  }

  const next: CrossingWorld = {
    ...world,
    lanes,
    playerColumn,
    playerRow,
    position,
    score: Math.max(world.score, position),
    nextLaneId,
    coinsCollected,
    coinJustCollected,
  };

  const over = crossingCollision(next);
  return {
    ...next,
    over,
    deathReason: over ? detectDeathReason(next) : undefined,
  };
}

// Moves traffic, trains, and drifting river logs forward for one animation frame.
export function advanceCrossing(
  world: CrossingWorld,
  seconds: number,
  random: () => number = Math.random,
): CrossingWorld {
  if (world.over || !Number.isFinite(seconds) || seconds <= 0) return world;
  const delta = Math.min(seconds, 0.05);

  let playerColumn = world.playerColumn;
  const standingLane = world.lanes[world.playerRow];
  if (
    standingLane?.type === "water" &&
    standingLane.waterStyle === "logs" &&
    standingLane.logSpeed
  ) {
    playerColumn += standingLane.direction * standingLane.logSpeed * delta;
  }

  const lanes = world.lanes.map((lane) => {
    if (lane.type === "grass") return lane;
    if (lane.type === "water") {
      if (lane.waterStyle !== "logs" || !lane.logs || !lane.logSpeed) {
        return lane;
      }
      const trackSpan = CROSSING.columns + 14;
      const logs = lane.logs.map((log) => ({
        ...log,
        x:
          wrap(log.x + 7 + lane.direction * lane.logSpeed! * delta, trackSpan) -
          7,
      }));
      return { ...lane, logs };
    }

    const moved = lane.vehicles.map((vehicle) => ({
      ...vehicle,
      x: vehicle.x + lane.direction * vehicle.speed * delta,
    }));

    // Prevent overtaking/overlapping vehicles in the same road lane by maintaining a clean bumper gap.
    const minBumperGap = 0.5;
    if (lane.type === "road" && moved.length > 1) {
      moved.sort((a, b) => (lane.direction === 1 ? b.x - a.x : a.x - b.x));
      for (let i = 1; i < moved.length; i++) {
        const front = moved[i - 1];
        const curr = moved[i];
        if (lane.direction === 1) {
          const maxLeft = front.x - minBumperGap - curr.width;
          if (curr.x > maxLeft) {
            curr.x = maxLeft;
            curr.speed = Math.min(curr.speed, front.speed);
          }
        } else {
          const minLeft = front.x + front.width + minBumperGap;
          if (curr.x < minLeft) {
            curr.x = minLeft;
            curr.speed = Math.min(curr.speed, front.speed);
          }
        }
      }
    }

    let vehicles = moved.filter((vehicle) =>
      lane.direction === 1
        ? vehicle.x < CROSSING.columns + 6.5
        : vehicle.x + vehicle.width > -6.5,
    );

    let spawnIn = lane.spawnIn - delta;
    let spawnCount = lane.spawnCount;

    if (spawnIn <= 0) {
      const kind: CrossingVehicleKind =
        lane.type === "rail" ? "train" : pickRoadKind(random());
      const width = vehicleWidth(kind, random);
      const spawnX =
        lane.direction === 1 ? -width - 5.2 : CROSSING.columns + 5.2;
      const entryClear = vehicles.every((vehicle) =>
        lane.direction === 1
          ? vehicle.x > spawnX + width + 1.8
          : vehicle.x + vehicle.width < spawnX - 1.8,
      );
      if (entryClear) {
        vehicles = [
          ...vehicles,
          {
            id: lane.id * 1000 + spawnCount,
            x: spawnX,
            width,
            kind,
            speed:
              kind === "train" ? 12 + random() * 8 : 0.7 + random() * 1.35,
            colorVariant: (lane.id + spawnCount) % 4,
          },
        ];
        spawnCount += 1;
        spawnIn =
          lane.type === "rail" ? 3 + random() * 4 : 0.9 + random() * 2.6;
      } else {
        spawnIn = 0.2 + random() * 0.25;
      }
    }

    return { ...lane, vehicles, spawnIn, spawnCount };
  });

  const next: CrossingWorld = {
    ...world,
    lanes,
    playerColumn,
    coinJustCollected: false,
  };
  const over = crossingCollision(next);
  return {
    ...next,
    over,
    deathReason: over ? detectDeathReason(next) : undefined,
  };
}
