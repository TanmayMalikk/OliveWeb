// Cover users: 1:1 2048 sliding puzzle logic with persistent tile IDs and merge sources for smooth slide animation.
export type MergeDirection = "up" | "down" | "left" | "right";

export interface GroveTileSource {
  id: number;
  value: number;
}

export interface GroveTile {
  id: number;
  value: number;
  merged?: boolean;
  isNew?: boolean;
  mergedFrom?: [GroveTileSource, GroveTileSource];
}

export interface GroveMergeWorld {
  grid: (GroveTile | null)[];
  score: number;
  lastGained?: number;
  moves: number;
  nextId: number;
  won: boolean;
  keepPlaying: boolean;
  over: boolean;
}

export const MERGE_STAGES: Record<number, string> = {
  2: "Seed",
  4: "Sprout",
  8: "Leaf",
  16: "Bud",
  32: "Olive",
  64: "Sprig",
  128: "Blossom",
  256: "Branch",
  512: "Canopy",
  1024: "Orchard",
  2048: "Ancient Olive",
  4096: "Evergreen",
  8192: "Grove Crown",
};

const SIZE = 4;

const validRandom = (random: () => number) => {
  const value = random();
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0.5;
};

function spawnTile(
  grid: (GroveTile | null)[],
  nextId: number,
  random: () => number,
): { grid: (GroveTile | null)[]; nextId: number } {
  const emptyIndices = grid
    .map((cell, index) => (cell === null ? index : -1))
    .filter((index) => index !== -1);
  if (emptyIndices.length === 0) return { grid, nextId };
  const picked =
    emptyIndices[
      Math.floor(validRandom(random) * emptyIndices.length) % emptyIndices.length
    ];
  // Classic 2048 spawn odds: 90% 2-tile, 10% 4-tile.
  const value = validRandom(random) < 0.9 ? 2 : 4;
  const copy = [...grid];
  copy[picked] = { id: nextId, value, isNew: true };
  return { grid: copy, nextId: nextId + 1 };
}

export function newGroveMerge(
  random: () => number = Math.random,
): GroveMergeWorld {
  let grid: (GroveTile | null)[] = Array(SIZE * SIZE).fill(null);
  let nextId = 1;
  ({ grid, nextId } = spawnTile(grid, nextId, random));
  ({ grid, nextId } = spawnTile(grid, nextId, random));
  return {
    grid,
    score: 0,
    lastGained: 0,
    moves: 0,
    nextId,
    won: false,
    keepPlaying: false,
    over: false,
  };
}

export function canMergeMove(grid: (GroveTile | null)[]): boolean {
  for (let row = 0; row < SIZE; row++) {
    for (let col = 0; col < SIZE; col++) {
      const tile = grid[row * SIZE + col];
      if (!tile) return true;
      if (col + 1 < SIZE && grid[row * SIZE + col + 1]?.value === tile.value)
        return true;
      if (row + 1 < SIZE && grid[(row + 1) * SIZE + col]?.value === tile.value)
        return true;
    }
  }
  return false;
}

function slideLine(
  line: (GroveTile | null)[],
  startNextId: number,
): {
  line: (GroveTile | null)[];
  gained: number;
  nextId: number;
  mergedLevel: number;
} {
  const tiles = line
    .filter((tile): tile is GroveTile => tile !== null)
    .map((tile) => ({ id: tile.id, value: tile.value }));
  const output: (GroveTile | null)[] = [];
  let gained = 0;
  let nextId = startNextId;
  let mergedLevel = 0;

  let i = 0;
  while (i < tiles.length) {
    const current = tiles[i];
    const next = tiles[i + 1];
    if (next && next.value === current.value) {
      const value = current.value * 2;
      gained += value;
      mergedLevel = Math.max(mergedLevel, Math.log2(value));
      output.push({
        id: nextId++,
        value,
        merged: true,
        mergedFrom: [
          { id: current.id, value: current.value },
          { id: next.id, value: next.value },
        ],
      });
      i += 2;
    } else {
      output.push({ id: current.id, value: current.value });
      i += 1;
    }
  }
  while (output.length < SIZE) output.push(null);
  return { line: output, gained, nextId, mergedLevel };
}

export function moveGroveMerge(
  world: GroveMergeWorld,
  direction: MergeDirection,
  random: () => number = Math.random,
): { world: GroveMergeWorld; moved: boolean; mergedLevel: number } {
  if (world.over || (world.won && !world.keepPlaying)) {
    return { world, moved: false, mergedLevel: 0 };
  }

  const nextGrid: (GroveTile | null)[] = Array(SIZE * SIZE).fill(null);
  let score = world.score;
  let gained = 0;
  let nextId = world.nextId;
  let mergedLevel = 0;
  let moved = false;

  for (let outer = 0; outer < SIZE; outer++) {
    const line: (GroveTile | null)[] = [];
    for (let inner = 0; inner < SIZE; inner++) {
      const index =
        direction === "left"
          ? outer * SIZE + inner
          : direction === "right"
            ? outer * SIZE + (SIZE - 1 - inner)
            : direction === "up"
              ? inner * SIZE + outer
              : (SIZE - 1 - inner) * SIZE + outer;
      line.push(world.grid[index]);
    }
    const slid = slideLine(line, nextId);
    nextId = slid.nextId;
    score += slid.gained;
    gained += slid.gained;
    mergedLevel = Math.max(mergedLevel, slid.mergedLevel);

    for (let inner = 0; inner < SIZE; inner++) {
      const index =
        direction === "left"
          ? outer * SIZE + inner
          : direction === "right"
            ? outer * SIZE + (SIZE - 1 - inner)
            : direction === "up"
              ? inner * SIZE + outer
              : (SIZE - 1 - inner) * SIZE + outer;
      nextGrid[index] = slid.line[inner];
      if (slid.line[inner]?.value !== world.grid[index]?.value) moved = true;
    }
  }

  if (!moved) return { world, moved: false, mergedLevel: 0 };

  const spawned = spawnTile(nextGrid, nextId, random);
  const won =
    world.won ||
    spawned.grid.some((tile) => tile !== null && tile.value >= 2048);
  const over = !canMergeMove(spawned.grid);

  return {
    world: {
      grid: spawned.grid,
      score,
      lastGained: gained,
      moves: world.moves + 1,
      nextId: spawned.nextId,
      won,
      keepPlaying: world.keepPlaying,
      over,
    },
    moved: true,
    mergedLevel,
  };
}
