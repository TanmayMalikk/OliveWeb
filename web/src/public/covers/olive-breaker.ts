// Cover users: 1:1 Brick Breaker physics, classic row tiers, power-ups, and wave patterns for Canopy Breaker.
export const BREAKER = {
  width: 640,
  height: 460,
  paddleY: 418,
  paddleHeight: 16,
  paddleWidth: 108,
  widePaddleWidth: 164,
  minPaddleWidth: 82,
  ballRadius: 9,
  baseSpeed: 320,
  maxSpeed: 540,
  brickCols: 8,
  brickRows: 5,
  brickGap: 7,
  brickTop: 46,
  brickHeight: 22,
  sidePadding: 22,
};

export type BreakerBrickTone = "gold" | "cedar" | "olive" | "sage" | "sprout";
export type BreakerPowerKind = "wide" | "twin" | "slow" | "fire" | "life";

export interface BreakerBrick {
  id: number;
  x: number;
  y: number;
  width: number;
  height: number;
  hp: number;
  maxHp: number;
  points?: number;
  tone: BreakerBrickTone;
}

export interface BreakerBall {
  x: number;
  y: number;
  vx: number;
  vy: number;
}

export interface BreakerPower {
  id: number;
  x: number;
  y: number;
  vy: number;
  kind: BreakerPowerKind;
}

export interface BreakerParticle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number;
  maxLife: number;
  color: string;
  size: number;
}

export interface BreakerWorld {
  paddleX: number;
  paddleWidth: number;
  wideTimer: number;
  slowTimer: number;
  fireTimer: number;
  ceilingBoosted: boolean;
  balls: BreakerBall[];
  bricks: BreakerBrick[];
  powers: BreakerPower[];
  particles: BreakerParticle[];
  score: number;
  combo: number;
  lives: number;
  wave: number;
  nextId: number;
  stuckToPaddle: boolean;
  over: boolean;
}

const TONE_COLORS: Record<BreakerBrickTone, string> = {
  gold: "#d49b38",
  cedar: "#c06e3f",
  olive: "#435e35",
  sage: "#7d9964",
  sprout: "#9dbb6c",
};

const ROW_TIERS: Array<{ tone: BreakerBrickTone; points: number }> = [
  { tone: "gold", points: 50 },
  { tone: "cedar", points: 40 },
  { tone: "olive", points: 30 },
  { tone: "sage", points: 20 },
  { tone: "sprout", points: 10 },
];

const validRandom = (random: () => number) => {
  const value = random();
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0.5;
};

export function createBreakerWave(
  wave: number,
  random: () => number = Math.random,
): BreakerBrick[] {
  const safeWave = Math.max(1, Math.floor(wave));
  const pattern = (safeWave - 1) % 4;
  const rows = Math.min(6, BREAKER.brickRows + (safeWave >= 2 ? 1 : 0));
  const usableWidth = BREAKER.width - BREAKER.sidePadding * 2;
  const brickWidth =
    (usableWidth - BREAKER.brickGap * (BREAKER.brickCols - 1)) /
    BREAKER.brickCols;
  const bricks: BreakerBrick[] = [];
  let id = safeWave * 100;

  for (let row = 0; row < rows; row++) {
    const tier = ROW_TIERS[Math.min(ROW_TIERS.length - 1, row)];
    for (let col = 0; col < BREAKER.brickCols; col++) {
      // Classic Brick Breaker level patterns after wave 1.
      if (pattern === 1 && (col === 3 || col === 4) && row >= 1 && row <= rows - 2) {
        continue; // Twin columns / central alley
      }
      if (pattern === 2 && (row + col) % 2 === 1 && row > 0 && row < rows - 1) {
        continue; // Checkerboard orchard
      }
      if (pattern === 3) {
        const dist = Math.abs(col - 3.5);
        if (row === 0 && dist > 2.6) continue;
        if (row === rows - 1 && dist > 2.6) continue;
      }

      const roll = validRandom(random);
      let maxHp = 1;
      if (safeWave >= 2 && row === 0) {
        maxHp = safeWave >= 3 ? 3 : 2;
      } else if (safeWave >= 3 && row === 1 && (col % 2 === 0 || roll > 0.55)) {
        maxHp = 2;
      }

      bricks.push({
        id: ++id,
        x: BREAKER.sidePadding + col * (brickWidth + BREAKER.brickGap),
        y: BREAKER.brickTop + row * (BREAKER.brickHeight + BREAKER.brickGap),
        width: brickWidth,
        height: BREAKER.brickHeight,
        hp: maxHp,
        maxHp,
        points: tier.points,
        tone: tier.tone,
      });
    }
  }
  return bricks;
}

export function newBreaker(random: () => number = Math.random): BreakerWorld {
  const paddleX = BREAKER.width / 2;
  return {
    paddleX,
    paddleWidth: BREAKER.paddleWidth,
    wideTimer: 0,
    slowTimer: 0,
    fireTimer: 0,
    ceilingBoosted: false,
    balls: [
      {
        x: paddleX,
        y: BREAKER.paddleY - BREAKER.ballRadius - 1,
        vx: 130,
        vy: -BREAKER.baseSpeed,
      },
    ],
    bricks: createBreakerWave(1, random),
    powers: [],
    particles: [],
    score: 0,
    combo: 0,
    lives: 3,
    wave: 1,
    nextId: 1,
    stuckToPaddle: true,
    over: false,
  };
}

export function launchBreakerBall(
  world: BreakerWorld,
  angleOffset = 0.24,
): BreakerWorld {
  if (!world.stuckToPaddle || world.over) return world;
  const clampedOffset = Math.max(-0.58, Math.min(0.58, angleOffset));
  const angle = -Math.PI / 2 + clampedOffset;
  const speed = BREAKER.baseSpeed + Math.min(110, (world.wave - 1) * 18);
  return {
    ...world,
    stuckToPaddle: false,
    balls: [
      {
        x: world.paddleX,
        y: BREAKER.paddleY - BREAKER.ballRadius - 1,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
      },
    ],
  };
}

export function moveBreakerPaddle(
  world: BreakerWorld,
  targetX: number,
): BreakerWorld {
  if (!Number.isFinite(targetX)) return world;
  const half = world.paddleWidth / 2;
  const clampedX = Math.max(half + 8, Math.min(BREAKER.width - half - 8, targetX));
  if (!world.stuckToPaddle) return { ...world, paddleX: clampedX };
  return {
    ...world,
    paddleX: clampedX,
    balls: world.balls.map((ball) => ({ ...ball, x: clampedX })),
  };
}

export interface BreakerStepEvents {
  bouncedPaddle: boolean;
  hitBrick: boolean;
  powerUp: BreakerPowerKind | null;
  lostLife: boolean;
  clearedWave: boolean;
}

function normalizeMinVertical(ball: BreakerBall) {
  const speed = Math.hypot(ball.vx, ball.vy);
  if (speed < 1) return;
  const minVy = speed * 0.24;
  if (Math.abs(ball.vy) < minVy) {
    const signY = ball.vy >= 0 ? 1 : -1;
    const signX = ball.vx >= 0 ? 1 : -1;
    ball.vy = signY * minVy;
    ball.vx = signX * Math.sqrt(Math.max(1, speed * speed - ball.vy * ball.vy));
  }
}

export function advanceBreaker(
  world: BreakerWorld,
  seconds: number,
  random: () => number = Math.random,
): { world: BreakerWorld; events: BreakerStepEvents } {
  const events: BreakerStepEvents = {
    bouncedPaddle: false,
    hitBrick: false,
    powerUp: null,
    lostLife: false,
    clearedWave: false,
  };
  if (world.over || !Number.isFinite(seconds) || seconds <= 0) {
    return { world, events };
  }

  const dt = Math.min(seconds, 0.032);
  let wideTimer = Math.max(0, world.wideTimer - dt);
  let slowTimer = Math.max(0, world.slowTimer - dt);
  let fireTimer = Math.max(0, world.fireTimer - dt);
  let ceilingBoosted = world.ceilingBoosted;

  let paddleWidth =
    wideTimer > 0
      ? BREAKER.widePaddleWidth
      : Math.max(BREAKER.minPaddleWidth, BREAKER.paddleWidth - (world.wave - 1) * 3);
  const paddleHalf = paddleWidth / 2;
  const paddleX = Math.max(
    paddleHalf + 8,
    Math.min(BREAKER.width - paddleHalf - 8, world.paddleX),
  );

  // Floating brick particles.
  let particles = world.particles
    .map((p) => ({
      ...p,
      x: p.x + p.vx * dt,
      y: p.y + p.vy * dt,
      vy: p.vy + 240 * dt,
      life: p.life - dt,
    }))
    .filter((p) => p.life > 0)
    .slice(0, 80);

  if (world.stuckToPaddle) {
    return {
      world: {
        ...world,
        paddleX,
        paddleWidth,
        wideTimer,
        slowTimer,
        fireTimer,
        particles,
      },
      events,
    };
  }

  let bricks = world.bricks.map((b) => ({ ...b }));
  let powers = world.powers.map((p) => ({ ...p }));
  let score = world.score;
  let combo = world.combo;
  let lives = world.lives;
  let wave = world.wave;
  let nextId = world.nextId;

  const speedFactor = slowTimer > 0 ? 0.72 : 1;
  const substeps = 4;
  const subDt = (dt * speedFactor) / substeps;

  let balls = world.balls.map((b) => ({ ...b }));

  for (let step = 0; step < substeps; step++) {
    for (const ball of balls) {
      ball.x += ball.vx * subDt;
      ball.y += ball.vy * subDt;

      // Side walls (6px court border).
      if (ball.x - BREAKER.ballRadius <= 6) {
        ball.x = 6 + BREAKER.ballRadius;
        ball.vx = Math.abs(ball.vx);
      } else if (ball.x + BREAKER.ballRadius >= BREAKER.width - 6) {
        ball.x = BREAKER.width - 6 - BREAKER.ballRadius;
        ball.vx = -Math.abs(ball.vx);
      }

      // Top ceiling + classic Breakout first-ceiling speed bump.
      if (ball.y - BREAKER.ballRadius <= 6) {
        ball.y = 6 + BREAKER.ballRadius;
        ball.vy = Math.abs(ball.vy);
        if (!ceilingBoosted) {
          ceilingBoosted = true;
          const speed = Math.hypot(ball.vx, ball.vy);
          if (speed > 0 && speed < BREAKER.maxSpeed) {
            const boost = Math.min(1.12, BREAKER.maxSpeed / speed);
            ball.vx *= boost;
            ball.vy *= boost;
          }
        }
      }

      // Classic Breakout paddle bounce (angle strictly mapped across paddle width).
      if (
        ball.vy > 0 &&
        ball.y + BREAKER.ballRadius >= BREAKER.paddleY &&
        ball.y - BREAKER.ballRadius <= BREAKER.paddleY + BREAKER.paddleHeight &&
        ball.x >= paddleX - paddleHalf - BREAKER.ballRadius * 0.65 &&
        ball.x <= paddleX + paddleHalf + BREAKER.ballRadius * 0.65
      ) {
        const hitOffset = Math.max(-1, Math.min(1, (ball.x - paddleX) / paddleHalf));
        const maxAngle = (64 * Math.PI) / 180;
        const bounceAngle = hitOffset * maxAngle;
        const speed = Math.min(
          BREAKER.maxSpeed,
          Math.max(BREAKER.baseSpeed, Math.hypot(ball.vx, ball.vy) * 1.008),
        );
        ball.vx = Math.sin(bounceAngle) * speed;
        ball.vy = -Math.cos(bounceAngle) * speed;
        ball.y = BREAKER.paddleY - BREAKER.ballRadius - 0.5;
        combo = 0;
        events.bouncedPaddle = true;
      }

      // Brick collision (circle-vs-AABB closest-point separation).
      for (let i = 0; i < bricks.length; i++) {
        const brick = bricks[i];
        const closestX = Math.max(brick.x, Math.min(ball.x, brick.x + brick.width));
        const closestY = Math.max(brick.y, Math.min(ball.y, brick.y + brick.height));
        const dx = ball.x - closestX;
        const dy = ball.y - closestY;
        const distSq = dx * dx + dy * dy;

        if (distSq <= BREAKER.ballRadius * BREAKER.ballRadius) {
          const isFire = fireTimer > 0 && brick.hp <= 1;
          if (!isFire) {
            const leftPen = Math.abs(ball.x + BREAKER.ballRadius - brick.x);
            const rightPen = Math.abs(brick.x + brick.width - (ball.x - BREAKER.ballRadius));
            const topPen = Math.abs(ball.y + BREAKER.ballRadius - brick.y);
            const bottomPen = Math.abs(brick.y + brick.height - (ball.y - BREAKER.ballRadius));
            const minX = Math.min(leftPen, rightPen);
            const minY = Math.min(topPen, bottomPen);

            if (minX < minY) {
              ball.vx = ball.x < brick.x + brick.width / 2 ? -Math.abs(ball.vx) : Math.abs(ball.vx);
              ball.x =
                ball.x < brick.x + brick.width / 2
                  ? brick.x - BREAKER.ballRadius - 0.5
                  : brick.x + brick.width + BREAKER.ballRadius + 0.5;
            } else {
              ball.vy = ball.y < brick.y + brick.height / 2 ? -Math.abs(ball.vy) : Math.abs(ball.vy);
              ball.y =
                ball.y < brick.y + brick.height / 2
                  ? brick.y - BREAKER.ballRadius - 0.5
                  : brick.y + brick.height + BREAKER.ballRadius + 0.5;
            }
            normalizeMinVertical(ball);
          }

          brick.hp -= fireTimer > 0 ? 2 : 1;
          combo += 1;
          const comboMult = 1 + Math.min(3, Math.floor((combo - 1) / 3));
          score += (brick.points ?? 20) * comboMult;
          events.hitBrick = true;

          for (let p = 0; p < 6; p++) {
            const angle = (p / 6) * Math.PI * 2 + validRandom(random) * 0.5;
            const speed = 70 + validRandom(random) * 120;
            particles.push({
              x: brick.x + brick.width / 2,
              y: brick.y + brick.height / 2,
              vx: Math.cos(angle) * speed,
              vy: Math.sin(angle) * speed - 40,
              life: 0.42,
              maxLife: 0.42,
              color: TONE_COLORS[brick.tone],
              size: 4 + validRandom(random) * 3,
            });
          }

          if (brick.hp <= 0) {
            const cx = brick.x + brick.width / 2;
            const cy = brick.y + brick.height / 2;
            bricks.splice(i, 1);
            if (validRandom(random) < 0.24 && powers.length < 3) {
              const kinds: BreakerPowerKind[] = ["wide", "twin", "slow", "fire", "life"];
              const r = validRandom(random);
              const kind =
                r < 0.3
                  ? "wide"
                  : r < 0.62
                    ? "twin"
                    : r < 0.8
                      ? "fire"
                      : r < 0.93
                        ? "slow"
                        : "life";
              powers.push({
                id: ++nextId,
                x: cx,
                y: cy,
                vy: 132,
                kind: kinds.includes(kind) ? kind : "wide",
              });
            }
          }
          break;
        }
      }
    }
  }

  // Drop balls once they clear the bottom edge.
  balls = balls.filter((ball) => ball.y - BREAKER.ballRadius <= BREAKER.height);

  // Move and collect power-ups.
  const remainingPowers: BreakerPower[] = [];
  for (const power of powers) {
    const py = power.y + power.vy * dt;
    const caught =
      py + 12 >= BREAKER.paddleY &&
      py - 12 <= BREAKER.paddleY + BREAKER.paddleHeight &&
      Math.abs(power.x - paddleX) <= paddleHalf + 14;
    if (caught) {
      events.powerUp = power.kind;
      score += 50;
      if (power.kind === "wide") {
        wideTimer = 12;
        paddleWidth = BREAKER.widePaddleWidth;
      } else if (power.kind === "slow") {
        slowTimer = 9;
      } else if (power.kind === "fire") {
        fireTimer = 7;
      } else if (power.kind === "life") {
        lives = Math.min(4, lives + 1);
      } else if (power.kind === "twin" && balls.length > 0 && balls.length < 6) {
        // Classic Brick Breaker 3-ball split!
        const source = balls[0];
        const speed = Math.max(BREAKER.baseSpeed, Math.hypot(source.vx, source.vy));
        const baseVy = -Math.max(speed * 0.72, Math.abs(source.vy));
        balls.push(
          {
            x: source.x,
            y: source.y,
            vx: -speed * 0.58,
            vy: baseVy,
          },
          {
            x: source.x,
            y: source.y,
            vx: speed * 0.58,
            vy: baseVy,
          },
        );
      }
    } else if (py < BREAKER.height + 24) {
      remainingPowers.push({ ...power, y: py });
    }
  }
  powers = remainingPowers;

  let stuckToPaddle = false;
  let over = false;

  if (bricks.length === 0) {
    wave += 1;
    score += 200;
    bricks = createBreakerWave(wave, random);
    powers = [];
    wideTimer = 0;
    slowTimer = 0;
    fireTimer = 0;
    ceilingBoosted = false;
    stuckToPaddle = true;
    balls = [
      {
        x: paddleX,
        y: BREAKER.paddleY - BREAKER.ballRadius - 1,
        vx: 140,
        vy: -(BREAKER.baseSpeed + Math.min(110, (wave - 1) * 18)),
      },
    ];
    events.clearedWave = true;
  } else if (balls.length === 0) {
    lives -= 1;
    combo = 0;
    wideTimer = 0;
    slowTimer = 0;
    fireTimer = 0;
    ceilingBoosted = false;
    events.lostLife = true;
    if (lives <= 0) {
      over = true;
    } else {
      stuckToPaddle = true;
      balls = [
        {
          x: paddleX,
          y: BREAKER.paddleY - BREAKER.ballRadius - 1,
          vx: 130,
          vy: -(BREAKER.baseSpeed + Math.min(110, (wave - 1) * 18)),
        },
      ];
    }
  }

  return {
    world: {
      paddleX,
      paddleWidth,
      wideTimer,
      slowTimer,
      fireTimer,
      ceilingBoosted,
      balls,
      bricks,
      powers,
      particles,
      score,
      combo,
      lives,
      wave,
      nextId,
      stuckToPaddle,
      over,
    },
    events,
  };
}
