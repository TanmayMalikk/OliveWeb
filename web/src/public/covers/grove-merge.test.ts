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

  it("uses the classic spawn odds and leaves the board untouched on a no-op", () => {
    const rolls = [0.89, 0, 0.9, 0];
    const world = newGroveMerge(() => rolls.shift()!);
    expect(world.grid.filter(Boolean).map((tile) => tile?.value)).toEqual([2, 4]);
    expect(moveGroveMerge(world, "left", () => {
      throw new Error("A no-op must not spawn a tile");
    }).world).toBe(world);
  });

  it("pauses after creating 2048 and can continue from the same board", () => {
    const world: GroveMergeWorld = {
      ...newGroveMerge(),
      grid: [{ id: 1, value: 1024 }, { id: 2, value: 1024 }, ...Array(14).fill(null)],
    };
    const won = moveGroveMerge(world, "left", () => 0).world;
    expect(won.won).toBe(true);
    expect(won.score).toBe(2048);
    expect(moveGroveMerge(won, "right").moved).toBe(false);
    expect(moveGroveMerge({ ...won, keepPlaying: true }, "right").moved).toBe(true);
  });
});
