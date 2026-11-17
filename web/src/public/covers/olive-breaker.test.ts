// Tests 1:1 Brick Breaker physics, collisions, multi-ball, fire-olive, and waves for Canopy Breaker.
import { describe, expect, it } from "vitest";
import {
  BREAKER,
  advanceBreaker,
  createBreakerWave,
  launchBreakerBall,
  moveBreakerPaddle,
  newBreaker,
} from "./olive-breaker";

describe("Canopy Breaker physics", () => {
  it("starts with the ball resting on the paddle and a wave of bricks", () => {
    const world = newBreaker(() => 0.3);
    expect(world.stuckToPaddle).toBe(true);
    expect(world.lives).toBe(3);
    expect(world.bricks.length).toBeGreaterThan(20);
    expect(world.balls[0].x).toBe(world.paddleX);
  });

  it("clamps paddle movement inside the play walls and moves attached balls", () => {
    let world = newBreaker(() => 0.3);
    world = moveBreakerPaddle(world, -50);
    expect(world.paddleX).toBeGreaterThanOrEqual(world.paddleWidth / 2 + 8);
    expect(world.balls[0].x).toBe(world.paddleX);
    world = moveBreakerPaddle(world, BREAKER.width + 200);
    expect(world.paddleX).toBeLessThanOrEqual(BREAKER.width - world.paddleWidth / 2 - 8);
  });

  it("launches the ball, hits bricks, and advances safely on non-finite dt", () => {
    let world = launchBreakerBall(newBreaker(() => 0.3));
    expect(world.stuckToPaddle).toBe(false);
    const bad = advanceBreaker(world, Number.NaN);
    expect(bad.world).toBe(world);

    const lowBrick = {
      id: 999,
      x: world.paddleX - 40,
      y: world.balls[0].y - 30,
      width: 80,
      height: 24,
      hp: 1,
      maxHp: 1,
      points: 50,
      tone: "gold" as const,
    };
    world = { ...world, bricks: [lowBrick, ...createBreakerWave(1)] };
    const step = advanceBreaker(world, 0.032, () => 0.9);
    expect(step.events.hitBrick).toBe(true);
    expect(step.world.score).toBeGreaterThan(0);
  });

  it("splits into 3 balls on multi-ball power-up and keeps playing while any ball remains", () => {
    const launched = launchBreakerBall(newBreaker(() => 0.3));
    const catchingMulti = {
      ...launched,
      powers: [
        {
          id: 50,
          x: launched.paddleX,
          y: BREAKER.paddleY,
          vy: 120,
          kind: "twin" as const,
        },
      ],
    };
    const split = advanceBreaker(catchingMulti, 0.02, () => 0.5);
    expect(split.events.powerUp).toBe("twin");
    expect(split.world.balls).toHaveLength(3);

    const oneBallLost = {
      ...split.world,
      balls: [
        split.world.balls[0],
        { x: 200, y: BREAKER.height + 50, vx: 0, vy: 220 },
      ],
    };
    const survived = advanceBreaker(oneBallLost, 0.016, () => 0.5);
    expect(survived.events.lostLife).toBe(false);
    expect(survived.world.lives).toBe(3);
    expect(survived.world.balls).toHaveLength(1);
  });

  it("loses a life when a ball falls off the bottom and ends at 0 lives", () => {
    const launched = launchBreakerBall(newBreaker(() => 0.3));
    const falling = {
      ...launched,
      lives: 1,
      balls: [{ x: 200, y: BREAKER.height + 5, vx: 0, vy: 220 }],
    };
    const next = advanceBreaker(falling, 0.02);
    expect(next.events.lostLife).toBe(true);
    expect(next.world.lives).toBe(0);
    expect(next.world.over).toBe(true);
  });
});
