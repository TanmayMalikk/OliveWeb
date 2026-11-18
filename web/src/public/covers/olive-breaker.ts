// Single-player arcade Breakout rules for Canopy Breaker.
export const BREAKER = {
  width: 640,
  height: 460,
  paddleY: 418,
  paddleHeight: 16,
  paddleWidth: 108,
  ballRadius: 7,
  brickCols: 14,
  brickRows: 8,
  brickGap: 3,
  brickTop: 46,
  brickHeight: 15,
  sidePadding: 22,
  wall: 6,
  wallsToClear: 2,
};

const ROW_TIERS = [
  { tone: "olive", points: 7 },
  { tone: "cedar", points: 5 },
  { tone: "sage", points: 3 },
  { tone: "gold", points: 1 },
] as const;

const SPEEDS = [300, 370, 440, 520];
const POWER_KINDS = ["wide", "slow", "multiball"] as const;
export type BreakerPowerKind = (typeof POWER_KINDS)[number];
type PowerDrop = { id: number; kind: BreakerPowerKind; x: number; y: number };
const DROP_SPEED = 128;
const DROP_HALF_WIDTH = 22;
const DROP_HALF_HEIGHT = 9;

interface BreakerBrick {
  id: number;
  x: number;
  y: number;
  width: number;
  height: number;
  points: number;
  tone: (typeof ROW_TIERS)[number]["tone"];
}

interface BreakerBall {
  x: number;
  y: number;
  vx: number;
  vy: number;
  bonus?: boolean;
}

export interface BreakerWorld {
  paddleX: number;
  paddleWidth: number;
  balls: BreakerBall[];
  bricks: BreakerBrick[];
  score: number;
  lives: number;
  wave: number;
  hits: number;
  speedTier: number;
  stuckToPaddle: boolean;
  over: boolean;
  won: boolean;
  drops: PowerDrop[];
  wideFor: number;
  slowFor: number;
  ceilingHit: boolean;
  dropsSpawned: number;
}

export function createBreakerWave(): BreakerBrick[] {
  const { brickCols, brickRows, brickGap, brickTop, brickHeight, sidePadding } = BREAKER;
  const width = (BREAKER.width - sidePadding * 2 - (brickCols - 1) * brickGap) / brickCols;
  return Array.from({ length: brickCols * brickRows }, (_, id) => {
    const row = Math.floor(id / brickCols);
    return {
      id,
      x: sidePadding + (id % brickCols) * (width + brickGap),
      y: brickTop + row * (brickHeight + brickGap),
      width,
      height: brickHeight,
      ...ROW_TIERS[Math.floor(row / 2)],
    };
  });
}

function restingBall(x: number): BreakerBall {
  return { x, y: BREAKER.paddleY - BREAKER.ballRadius - 1, vx: 0, vy: 0 };
}

export function newBreaker(): BreakerWorld {
  const paddleX = BREAKER.width / 2;
  return {
    paddleX,
    paddleWidth: BREAKER.paddleWidth,
    balls: [restingBall(paddleX)],
    bricks: createBreakerWave(),
    score: 0,
    lives: 3,
    wave: 1,
    hits: 0,
    speedTier: 0,
    stuckToPaddle: true,
    over: false,
    won: false,
    drops: [],
    wideFor: 0,
    slowFor: 0,
    ceilingHit: false,
    dropsSpawned: 0,
  };
}

export function launchBreakerBall(world: BreakerWorld): BreakerWorld {
  if (!world.stuckToPaddle || world.over) return world;
  const angle = world.paddleX < BREAKER.width / 2 ? 0.32 : -0.32;
  return {
    ...world,
    balls: [{
      ...restingBall(world.paddleX),
      vx: Math.sin(angle) * SPEEDS[0],
      vy: -Math.cos(angle) * SPEEDS[0],
    }],
    stuckToPaddle: false,
  };
}

function clampPaddle(x: number, width: number) {
  const edge = width / 2 + BREAKER.wall;
  return Math.max(edge, Math.min(BREAKER.width - edge, x));
}

export function moveBreakerPaddle(world: BreakerWorld, targetX: number): BreakerWorld {
  if (!Number.isFinite(targetX)) return world;
  const paddleX = clampPaddle(targetX, world.paddleWidth);
  return {
    ...world,
    paddleX,
    balls: world.stuckToPaddle ? [restingBall(paddleX)] : world.balls,
  };
}

export function advanceBreaker(world: BreakerWorld, seconds: number) {
  const events = { bouncedPaddle: false, hitBrick: false, lostLife: false, clearedWave: false, caught: [] as BreakerPowerKind[] };
  if (world.over || world.stuckToPaddle || !Number.isFinite(seconds) || seconds <= 0) {
    return { world, events };
  }

  const next = { ...world, balls: world.balls.map((ball) => ({ ...ball })), bricks: [...world.bricks] };
  const { ballRadius: radius, paddleY, wall } = BREAKER;
  const dt = Math.min(seconds, 0.05);
  next.wideFor = Math.max(0, world.wideFor - dt);
  next.slowFor = Math.max(0, world.slowFor - dt);
  next.paddleWidth = activePaddleWidth(next);
  next.drops = world.drops
    .map((drop) => ({ ...drop, y: drop.y + DROP_SPEED * dt }))
    .filter((drop) => {
      if (drop.y - DROP_HALF_HEIGHT > BREAKER.height) return false;
      const crossesPaddle = drop.y + DROP_HALF_HEIGHT >= paddleY &&
        drop.y - DROP_HALF_HEIGHT <= paddleY + BREAKER.paddleHeight;
      const insidePaddle = Math.abs(drop.x - next.paddleX) <= next.paddleWidth / 2 + DROP_HALF_WIDTH;
      if (!crossesPaddle || !insidePaddle) return true;
      if (drop.kind === "multiball") {
        if (next.balls.length < 2) {
          const existing = next.balls[0];
          const angle = existing && existing.vx > 0 ? -0.45 : 0.45;
          const speed = SPEEDS[next.speedTier];
          next.balls.push({
            ...restingBall(next.paddleX),
            vx: Math.sin(angle) * speed,
            vy: -Math.cos(angle) * speed,
            bonus: !existing?.bonus,
          });
          events.caught.push("multiball");
        }
        return false;
      }
      events.caught.push(drop.kind);
      if (drop.kind === "wide") next.wideFor = 12;
      if (drop.kind === "slow") next.slowFor = 8;
      return false;
    });
  next.paddleWidth = activePaddleWidth(next);
  next.paddleX = clampPaddle(next.paddleX, next.paddleWidth);
  const steps = Math.ceil((dt * SPEEDS[3]) / (radius / 2));
  const step = dt * (next.slowFor > 0 ? 0.72 : 1) / steps;

  for (let i = 0; i < steps; i++) {
    for (const ball of next.balls) {
      const previousY = ball.y;
      ball.x += ball.vx * step;
      ball.y += ball.vy * step;

      const minX = wall + radius;
      const maxX = BREAKER.width - minX;
      if (ball.x <= minX || ball.x >= maxX) {
        ball.x = Math.max(minX, Math.min(maxX, ball.x));
        ball.vx = ball.x === minX ? Math.abs(ball.vx) : -Math.abs(ball.vx);
      }
      if (ball.y <= wall + radius) {
        ball.y = wall + radius;
        ball.vy = Math.abs(ball.vy);
        next.ceilingHit = true;
        next.paddleWidth = activePaddleWidth(next);
      }

      if (ball.vy > 0 && previousY + radius <= paddleY && ball.y + radius >= paddleY) {
        const half = next.paddleWidth / 2;
        const offset = (ball.x - next.paddleX) / half;
        if (Math.abs(offset) <= 1 + radius / half) {
          const angle = Math.max(-1, Math.min(1, offset)) * (Math.PI / 3);
          ball.vx = Math.sin(angle) * SPEEDS[next.speedTier];
          ball.vy = -Math.cos(angle) * SPEEDS[next.speedTier];
          ball.y = paddleY - radius;
          events.bouncedPaddle = true;
        }
      }

      const index = next.bricks.findIndex((brick) => {
        const x = Math.max(brick.x, Math.min(brick.x + brick.width, ball.x));
        const y = Math.max(brick.y, Math.min(brick.y + brick.height, ball.y));
        return (ball.x - x) ** 2 + (ball.y - y) ** 2 <= radius ** 2;
      });
      if (index !== -1) {
        const [brick] = next.bricks.splice(index, 1);
        if (previousY <= brick.y - radius || previousY >= brick.y + brick.height + radius) {
          ball.y = previousY;
          ball.vy = -ball.vy;
        } else {
          const left = ball.x < brick.x + brick.width / 2;
          ball.x = left ? brick.x - radius : brick.x + brick.width + radius;
          ball.vx = left ? -Math.abs(ball.vx) : Math.abs(ball.vx);
        }

        next.score += brick.points;
        next.hits++;
        if (next.hits === 2 || next.hits % 5 === 0) {
          const id = next.dropsSpawned++;
          next.drops.push({
            id,
            kind: POWER_KINDS[id % POWER_KINDS.length],
            x: brick.x + brick.width / 2,
            y: brick.y + brick.height / 2,
          });
        }
        const hitTier = next.hits >= 12 ? 2 : next.hits >= 4 ? 1 : 0;
        next.speedTier = Math.max(next.speedTier, hitTier, brick.points >= 5 ? 3 : 0);
        const speed = SPEEDS[next.speedTier];
        const angle = Math.atan2(ball.vy, ball.vx);
        ball.vy = (ball.vy < 0 ? -1 : 1) * Math.max(Math.abs(Math.sin(angle)), 0.22) * speed;
        ball.vx = (ball.vx < 0 ? -1 : 1) * Math.sqrt(Math.max(0, speed ** 2 - ball.vy ** 2));
        events.hitBrick = true;

        if (next.bricks.length === 0) {
          events.clearedWave = true;
          if (next.wave === BREAKER.wallsToClear) {
            next.won = true;
            next.over = true;
          } else {
            next.wave++;
            next.bricks = createBreakerWave();
            // Serve from below the new wall instead of spawning bricks around the live ball.
            next.stuckToPaddle = true;
            next.hits = 0;
            next.speedTier = 0;
            resetPowerUps(next);
            next.balls = [restingBall(next.paddleX)];
          }
          return { world: next, events };
        }
      }
    }
  }

  next.balls = next.balls.filter((ball) => ball.y - radius <= BREAKER.height);
  if (!next.balls.length) {
    next.lives--;
    next.over = next.lives === 0;
    next.stuckToPaddle = !next.over;
    resetPowerUps(next);
    next.hits = 0;
    next.speedTier = 0;
    next.balls = next.over ? [] : [restingBall(next.paddleX)];
    events.lostLife = true;
  }

  return { world: next, events };
}

function activePaddleWidth(world: BreakerWorld) {
  const size = world.ceilingHit ? BREAKER.paddleWidth / 2 : BREAKER.paddleWidth;
  return world.wideFor > 0 ? size * 1.6 : size;
}

function resetPowerUps(world: BreakerWorld) {
  world.drops = [];
  world.wideFor = 0;
  world.slowFor = 0;
  world.ceilingHit = false;
  world.paddleWidth = BREAKER.paddleWidth;
  world.paddleX = clampPaddle(world.paddleX, world.paddleWidth);
}
