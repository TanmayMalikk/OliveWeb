// Game rules shared by Tetris keyboard, touch, and gravity controls.
export const TETRIS_WIDTH = 10;
export const HIDDEN_ROWS = 20;
export const TETRIS_HEIGHT = 40;
export const MARATHON_GOAL = 150;
const LOCK_DELAY = 500;
const MAX_RESETS = 15;

const SHAPES = {
  I: ["....", "####", "....", "...."],
  J: ["#..", "###", "..."],
  L: ["..#", "###", "..."],
  O: ["##", "##"],
  S: [".##", "##.", "..."],
  T: [".#.", "###", "..."],
  Z: ["##.", ".##", "..."],
} as const;

export type Tetromino = keyof typeof SHAPES;
type Offset = readonly [number, number];
type Piece = { kind: Tetromino; x: number; y: number; rotation: number };
type Cell = Tetromino | null;
type Board = Cell[][];
export type TetrisAction = "left" | "right" | "down" | "drop" | "cw" | "ccw" | "hold";

export type TetrisWorld = {
  board: Board;
  piece: Piece;
  queue: Tetromino[];
  held: Tetromino | null;
  canHold: boolean;
  score: number;
  lines: number;
  over: boolean;
  won: boolean;
  gravityMs: number;
  lockMs: number;
  resets: number;
  lowestY: number;
  groundedOnce: boolean;
  lastKick: number | null;
  backToBack: boolean;
  lastClear: string;
};

const PIECES = Object.keys(SHAPES) as Tetromino[];
const LINE_POINTS = [0, 100, 300, 500, 800];
const emptyRow = (): Cell[] => Array(TETRIS_WIDTH).fill(null);
const STANDARD_KICKS: Record<string, Offset[]> = {
  "01": [[-1, 0], [-1, 1], [0, -2], [-1, -2]],
  "10": [[1, 0], [1, -1], [0, 2], [1, 2]],
  "12": [[1, 0], [1, -1], [0, 2], [1, 2]],
  "21": [[-1, 0], [-1, 1], [0, -2], [-1, -2]],
  "23": [[1, 0], [1, 1], [0, -2], [1, -2]],
  "32": [[-1, 0], [-1, -1], [0, 2], [-1, 2]],
  "30": [[-1, 0], [-1, -1], [0, 2], [-1, 2]],
  "03": [[1, 0], [1, 1], [0, -2], [1, -2]],
};
const I_KICKS: Record<string, Offset[]> = {
  "01": [[-2, 0], [1, 0], [-2, -1], [1, 2]],
  "10": [[2, 0], [-1, 0], [2, 1], [-1, -2]],
  "12": [[-1, 0], [2, 0], [-1, 2], [2, -1]],
  "21": [[1, 0], [-2, 0], [1, -2], [-2, 1]],
  "23": [[2, 0], [-1, 0], [2, 1], [-1, -2]],
  "32": [[-2, 0], [1, 0], [-2, -1], [1, 2]],
  "30": [[1, 0], [-2, 0], [1, -2], [-2, 1]],
  "03": [[-1, 0], [2, 0], [-1, 2], [2, -1]],
};

export function tetrisLevel(lines: number) {
  return Math.min(15, Math.floor(lines / 10) + 1);
}

export function gravityInterval(lines: number) {
  const level = tetrisLevel(lines) - 1;
  return 1000 * (0.8 - level * 0.007) ** level;
}

function bag(random: () => number) {
  const pieces = [...PIECES];
  for (let i = pieces.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [pieces[i], pieces[j]] = [pieces[j], pieces[i]];
  }
  return pieces;
}

const spawn = (kind: Tetromino): Piece => ({
  kind, rotation: 0, x: kind === "O" ? 4 : 3, y: HIDDEN_ROWS - 2,
});

export function pieceOffsets(kind: Tetromino, rotation = 0): Offset[] {
  const shape = SHAPES[kind];
  const last = shape.length - 1;
  return shape.flatMap((row, y) => [...row].flatMap((cell, x): Offset[] => {
    if (cell !== "#") return [];
    if (kind === "O" || rotation === 0) return [[x, y]];
    if (rotation === 1) return [[last - y, x]];
    if (rotation === 2) return [[last - x, last - y]];
    return [[y, last - x]];
  }));
}

export function pieceCells(piece: Piece): Offset[] {
  return pieceOffsets(piece.kind, piece.rotation).map(([x, y]) => [x + piece.x, y + piece.y]);
}

function fits(board: Board, piece: Piece) {
  return pieceCells(piece).every(([x, y]) =>
    x >= 0 && x < TETRIS_WIDTH && y >= 0 && y < TETRIS_HEIGHT && board[y][x] === null,
  );
}

export function landingPiece(world: TetrisWorld): Piece {
  let piece = world.piece;
  while (fits(world.board, { ...piece, y: piece.y + 1 })) {
    piece = { ...piece, y: piece.y + 1 };
  }
  return piece;
}

export function newTetris(random = Math.random): TetrisWorld {
  const [first, ...queue] = bag(random);
  const board = Array.from({ length: TETRIS_HEIGHT }, emptyRow);
  return {
    board,
    ...spawnState(board, first),
    queue,
    held: null,
    canHold: true,
    score: 0,
    lines: 0,
    won: false,
    backToBack: false,
    lastClear: "",
  };
}

function spawnState(board: Board, kind: Tetromino) {
  const start = spawn(kind);
  const over = !fits(board, start);
  const lower = { ...start, y: start.y + 1 };
  const piece = !over && fits(board, lower) ? lower : start;
  return {
    piece, over, gravityMs: 0, lockMs: 0, resets: 0, lowestY: piece.y,
    groundedOnce: grounded(board, piece), lastKick: null,
  };
}

function nextPiece(world: TetrisWorld, random: () => number, held?: Tetromino): TetrisWorld {
  if (held) {
    return { ...world, ...spawnState(world.board, held) };
  }
  const [next, ...rest] = world.queue;
  const queue = rest.length >= 6 ? rest : [...rest, ...bag(random)];
  return { ...world, ...spawnState(world.board, next), queue };
}

function grounded(board: Board, piece: Piece) {
  return !fits(board, { ...piece, y: piece.y + 1 });
}

function tSpin(board: Board, piece: Piece, kick: number | null) {
  if (piece.kind !== "T" || kick === null) return null;
  const corners = [[0, 0], [2, 0], [2, 2], [0, 2]].map(([dx, dy]) => {
    const x = piece.x + dx;
    const y = piece.y + dy;
    return x < 0 || x >= TETRIS_WIDTH || y < 0 || y >= TETRIS_HEIGHT || board[y][x] !== null;
  });
  if (corners.filter(Boolean).length < 3) return null;
  return kick === 4 || (corners[piece.rotation] && corners[(piece.rotation + 1) % 4])
    ? "full" : "mini";
}

function lock(world: TetrisWorld, piece: Piece, dropPoints: number, random: () => number) {
  const cells = pieceCells(piece);
  const spin = tSpin(world.board, piece, piece.y === world.piece.y ? world.lastKick : null);
  const stamped = world.board.map((row) => [...row]);
  for (const [x, y] of cells) stamped[y][x] = piece.kind;
  const remaining = stamped.filter((row) => row.includes(null));
  const cleared = TETRIS_HEIGHT - remaining.length;
  const board = [...Array.from({ length: cleared }, emptyRow), ...remaining];
  const difficult = cleared > 0 && (cleared === 4 || spin !== null);
  const points = spin === "full" ? [400, 800, 1200, 1600][cleared]
    : spin === "mini" ? [100, 200, 400][cleared] : LINE_POINTS[cleared];
  const bonus = difficult && world.backToBack;
  const clearName = ["", "Single", "Double", "Triple", "Tetris"][cleared];
  const lastClear = spin
    ? `${spin === "mini" ? "Mini T-Spin" : "T-Spin"}${clearName ? ` ${clearName}` : ""}`
    : clearName;
  const lines = world.lines + cleared;
  const result = {
    ...world,
    piece,
    board,
    lines,
    score: world.score + dropPoints + points * tetrisLevel(world.lines) * (bonus ? 1.5 : 1),
    canHold: true,
    backToBack: cleared ? difficult : world.backToBack,
    lastClear: bonus ? `Back-to-back ${lastClear}` : lastClear,
    won: lines >= MARATHON_GOAL,
    over: cells.every(([, y]) => y < HIDDEN_ROWS),
  };
  return result.won || result.over ? result : nextPiece(result, random);
}

function shifted(world: TetrisWorld, piece: Piece, kick: number | null, playerMove: boolean) {
  const reachedLowerRow = piece.y > world.lowestY;
  const rests = grounded(world.board, piece);
  const usedReset = playerMove && world.groundedOnce && !reachedLowerRow;
  return {
    ...world,
    piece,
    lastKick: kick,
    groundedOnce: rests || (world.groundedOnce && !reachedLowerRow),
    lowestY: Math.max(piece.y, world.lowestY),
    resets: reachedLowerRow ? 0 : world.resets + Number(usedReset),
    lockMs: reachedLowerRow || (usedReset && world.resets < MAX_RESETS) ? 0 : world.lockMs,
  };
}

export function stepTetris(world: TetrisWorld, action: TetrisAction, random = Math.random): TetrisWorld {
  if (world.over || world.won) return world;
  const { piece } = world;
  if (action === "hold") {
    if (!world.canHold) return world;
    return nextPiece({ ...world, held: piece.kind, canHold: false }, random, world.held ?? undefined);
  }
  if (action === "drop") {
    const landed = landingPiece(world);
    return lock(world, landed, (landed.y - piece.y) * 2, random);
  }
  if (action === "down") {
    const lower = { ...piece, y: piece.y + 1 };
    return fits(world.board, lower)
      ? { ...shifted(world, lower, null, false), score: world.score + 1, gravityMs: 0 }
      : world;
  }
  if (action === "left" || action === "right") {
    const moved = { ...piece, x: piece.x + (action === "left" ? -1 : 1) };
    return fits(world.board, moved) ? shifted(world, moved, null, true) : world;
  }
  if (piece.kind === "O") return world;
  const rotation = (piece.rotation + (action === "cw" ? 1 : 3)) % 4;
  const kicks = piece.kind === "I" ? I_KICKS : STANDARD_KICKS;
  const tests: Offset[] = [[0, 0], ...kicks[`${piece.rotation}${rotation}`]];
  for (const [index, [dx, dy]] of tests.entries()) {
    const rotated = { ...piece, rotation, x: piece.x + dx, y: piece.y - dy };
    if (fits(world.board, rotated)) return shifted(world, rotated, index, true);
  }
  return world;
}

export function advanceTetris(world: TetrisWorld, ms: number, softDrop = false, random = Math.random): TetrisWorld {
  if (world.over || world.won || !Number.isFinite(ms) || ms <= 0) return world;
  const speed = softDrop ? 20 : 1;
  const interval = gravityInterval(world.lines);
  let next = world;
  let remaining = ms;
  while (remaining > 0) {
    if (grounded(next.board, next.piece)) {
      const lockMs = next.lockMs + remaining;
      return lockMs >= LOCK_DELAY || next.resets >= MAX_RESETS
        ? lock(next, next.piece, 0, random)
        : { ...next, lockMs, groundedOnce: true };
    }
    const wait = Math.max(0, (interval - next.gravityMs) / speed);
    if (remaining < wait) return { ...next, gravityMs: next.gravityMs + remaining * speed };
    remaining -= wait;
    next = {
      ...shifted(next, { ...next.piece, y: next.piece.y + 1 }, null, false),
      gravityMs: 0,
      score: next.score + Number(softDrop),
    };
  }
  return next;
}
