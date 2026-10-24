// Tests the test behavior.
import { expect, it } from "vitest";
import { createMemoryBoard } from "./memory-board";

for (let size = 5; size <= 8; size++) {
  it(`builds a solvable ${size}x${size} board`, () => {
    const board = createMemoryBoard(size);
    expect(board).toHaveLength(size * size);
    const pairs = new Map<number, number>();
    for (const card of board)
      if (card !== -1) pairs.set(card, (pairs.get(card) ?? 0) + 1);
    expect(pairs.size).toBe(Math.floor((size * size) / 2));
    expect([...pairs.values()].every((count) => count === 2)).toBe(true);
    expect(board.filter((card) => card === -1)).toHaveLength(size % 2);
    if (size % 2) expect(board[Math.floor(board.length / 2)]).toBe(-1);
  });
}
it("rejects sizes outside the supported range", () => {
  for (const size of [4, 9, 12, 5.5, NaN])
    expect(() => createMemoryBoard(size)).toThrow();
});
