// Tests sliding merge rules and board checks for Grove 2048.
import { describe, expect, it } from "vitest";
import {
  canMergeMove,
  moveGroveMerge,
  newGroveMerge,
  type GroveMergeWorld,
} from "./grove-merge";

describe("Grove 2048 puzzle logic", () => {
  it("spawns two initial tiles on a 4x4 board", () => {
    const world = newGroveMerge(() => 0.2);
    expect(world.grid).toHaveLength(16);
    expect(world.grid.filter(Boolean)).toHaveLength(2);
    expect(world.score).toBe(0);
  });

  it("merges matching adjacent tiles once per move and adds points", () => {
    const world: GroveMergeWorld = {
      grid: [
        { id: 1, value: 4 },
        { id: 2, value: 4 },
        { id: 3, value: 4 },
        null,
        ...Array(12).fill(null),
      ],
      score: 0,
      moves: 0,
      nextId: 10,
      won: false,
      keepPlaying: false,
      over: false,
    };
    const { world: next, moved, mergedLevel } = moveGroveMerge(world, "left", () => 0.5);
    expect(moved).toBe(true);
    expect(mergedLevel).toBe(3); // 2^3 = 8
    expect(next.grid[0]?.value).toBe(8);
    expect(next.grid[1]?.value).toBe(4);
    expect(next.score).toBe(8);
    expect(next.moves).toBe(1);
  });

  it("detects when no valid moves remain", () => {
    const locked = Array.from({ length: 16 }, (_, i) => ({
      id: i + 1,
      value: ((Math.floor(i / 4) + i) % 2 === 0 ? 2 : 4),
    }));
    expect(canMergeMove(locked)).toBe(false);
    const unlocked = [...locked];
    unlocked[15] = { id: 99, value: unlocked[14].value };
    expect(canMergeMove(unlocked)).toBe(true);
  });
});
