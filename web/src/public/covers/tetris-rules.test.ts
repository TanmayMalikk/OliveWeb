import { describe, expect, it } from "vitest";
import { advanceTetris, gravityInterval, landingPiece, newTetris, pieceCells, stepTetris, TETRIS_HEIGHT } from "./tetris-rules";

describe("Tetris", () => {
  it("starts with one of each piece in a seven-piece bag", () => {
    const world = newTetris(() => 0);
    expect(new Set([world.piece.kind, ...world.queue]).size).toBe(7);
  });

  it("hard drops an I piece into a four-row gap and clears the stack", () => {
    const world = newTetris(() => 0);
    for (const row of world.board.slice(-4)) {
      row.fill("O");
      row[5] = null;
    }
    world.piece = { kind: "I", x: 3, y: 19, rotation: 1 };
    const target = landingPiece(world);
    expect(pieceCells(target)).toEqual([4, 3, 2, 1].map((offset) => [5, TETRIS_HEIGHT - offset]));
    const next = stepTetris(world, "drop");
    expect(next.lines).toBe(4);
    expect(next.score).toBe(800 + 2 * (target.y - world.piece.y));
    expect(next.board.flat().every((cell) => cell === null)).toBe(true);
  });

  it("holds once per falling piece and restores the held piece at spawn", () => {
    const world = newTetris(() => 0);
    const held = stepTetris(world, "hold");
    expect(held.held).toBe(world.piece.kind);
    expect(stepTetris(held, "hold")).toBe(held);
    const dropped = stepTetris(held, "drop");
    const swapped = stepTetris(dropped, "hold");
    expect(swapped.piece.kind).toBe(world.piece.kind);
    expect(swapped.piece.rotation).toBe(0);
    expect(swapped.queue).toBe(dropped.queue);
  });

  it("stops moving once the stack blocks the next spawn", () => {
    let world = newTetris(() => 0);
    for (let i = 0; i < 22 && !world.over; i++) world = stepTetris(world, "drop", () => 0);
    expect(world.over).toBe(true);
    expect(stepTetris(world, "left")).toBe(world);
  });

  it("uses the I-specific two-column SRS kick at the left wall", () => {
    const world = newTetris();
    world.piece = { kind: "I", rotation: 1, x: -2, y: 23 };
    const rotated = stepTetris(world, "ccw");
    expect(rotated.piece).toEqual({ kind: "I", rotation: 0, x: 0, y: 23 });
    expect(rotated.lastKick).toBe(1);
  });

  it("allows a grounded move to reset the 500 ms lock delay", () => {
    const initial = newTetris(() => 0);
    const onFloor = { ...initial, piece: landingPiece(initial) };
    const waiting = advanceTetris(onFloor, 499);
    expect(waiting.board).toBe(initial.board);
    const moved = stepTetris(waiting, "right");
    expect(advanceTetris(moved, 499).board).toBe(initial.board);
    expect(advanceTetris(moved, 500).board).not.toBe(initial.board);
    expect(advanceTetris({ ...moved, resets: 15 }, 1).board).not.toBe(initial.board);
  });

  it("speeds up gravity as levels advance and earns one point per soft-drop cell", () => {
    const world = newTetris();
    expect(gravityInterval(0)).toBe(1000);
    expect(gravityInterval(10)).toBe(793);
    expect(advanceTetris(world, 999).piece).toBe(world.piece);
    const fast = advanceTetris(world, 150, true);
    expect(fast.piece.y - world.piece.y).toBe(3);
    expect(fast.score).toBe(3);
  });

  it("recognizes a T-Spin and scores it without a line clear", () => {
    const world = newTetris();
    world.piece = { kind: "T", x: 3, y: 38, rotation: 0 };
    world.lastKick = 0;
    world.board[38][3] = "J";
    world.board[38][5] = "J";
    const result = stepTetris(world, "drop");
    expect(result.score).toBe(400);
    expect(result.lastClear).toBe("T-Spin");
  });

  it("awards a back-to-back bonus and finishes Marathon at 150 lines", () => {
    const world = newTetris();
    world.lines = 146;
    world.backToBack = true;
    world.piece = { kind: "I", x: 3, y: 36, rotation: 1 };
    for (const row of world.board.slice(-4)) {
      row.fill("O");
      row[5] = null;
    }
    const result = stepTetris(world, "drop");
    expect(result.lastClear).toBe("Back-to-back Tetris");
    expect(result.score).toBe(800 * 15 * 1.5);
    expect(result.lines).toBe(150);
    expect(result.won).toBe(true);
  });
});
