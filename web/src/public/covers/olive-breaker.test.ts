import { describe, expect, it } from "vitest";
import {
  BREAKER,
  advanceBreaker,
  launchBreakerBall,
  moveBreakerPaddle,
  newBreaker,
  type BreakerWorld,
} from "./olive-breaker";

function hitLastBrick(world: BreakerWorld) {
  const brick = world.bricks.at(-1)!;
  return advanceBreaker({
    ...world,
    stuckToPaddle: false,
    bricks: [brick],
    balls: [{ x: brick.x + brick.width / 2, y: brick.y + brick.height + 8, vx: 0, vy: -300 }],
  }, 0.016);
}

describe("Canopy Breaker arcade rules", () => {
  it("starts with one attached ball, three lives and a 448-point wall", () => {
    const world = newBreaker();
    expect(world.lives).toBe(3);
    expect(world.balls).toHaveLength(1);
    expect(world.bricks).toHaveLength(112);
    expect(world.bricks.reduce((score, brick) => score + brick.points, 0)).toBe(448);
    expect(world.bricks.filter((brick) => brick.points === 7)).toHaveLength(28);
    const moved = moveBreakerPaddle(world, -50);
    expect(moved.paddleX).toBe(world.paddleWidth / 2 + BREAKER.wall);
    expect(moved.balls[0].x).toBe(moved.paddleX);
    expect(launchBreakerBall(moved).stuckToPaddle).toBe(false);
  });

  it("uses one hit per brick and advances speed at the fourth hit", () => {
    const world = launchBreakerBall(newBreaker());
    const brick = world.bricks.at(-1)!;
    const result = advanceBreaker({
      ...world,
      hits: 3,
      balls: [{ x: brick.x + brick.width / 2, y: brick.y + brick.height + 8, vx: 0, vy: -300 }],
    }, 0.016);
    expect(result.events.hitBrick).toBe(true);
    expect(result.world.bricks).toHaveLength(111);
    expect(result.world.score).toBe(1);
    expect(result.world.speedTier).toBe(1);
    expect(result.world.balls[0].vy).toBeGreaterThan(0);
  });

  it("halves the paddle at the ceiling and restores it when the ball is lost", () => {
    const world = launchBreakerBall(newBreaker());
    const ceiling = advanceBreaker({
      ...world,
      balls: [{ x: 320, y: BREAKER.wall + BREAKER.ballRadius + 1, vx: 0, vy: -300 }],
    }, 0.016);
    expect(ceiling.world.paddleWidth).toBe(BREAKER.paddleWidth / 2);
    const miss = advanceBreaker({
      ...ceiling.world,
      balls: [{ x: 38, y: BREAKER.height + BREAKER.ballRadius, vx: 0, vy: 300 }],
    }, 0.016);
    expect(miss.events.lostLife).toBe(true);
    expect(miss.world.lives).toBe(2);
    expect(miss.world.paddleWidth).toBe(BREAKER.paddleWidth);
    expect(miss.world.stuckToPaddle).toBe(true);
  });

  it("loses the round when the last ball is missed", () => {
    const world = launchBreakerBall(newBreaker());
    const miss = advanceBreaker({
      ...world,
      lives: 1,
      balls: [{ x: 38, y: BREAKER.height + BREAKER.ballRadius, vx: 0, vy: 300 }],
    }, 0.016);
    expect(miss.world.over).toBe(true);
    expect(miss.world.won).toBe(false);
    expect(miss.world.lives).toBe(0);
  });

  it("spawns a visible drop on a brick hit and only applies it when caught", () => {
    const world = launchBreakerBall(newBreaker());
    const brick = world.bricks.at(-1)!;
    const spawned = advanceBreaker({
      ...world,
      hits: 1,
      balls: [{ x: brick.x + brick.width / 2, y: brick.y + brick.height + 8, vx: 0, vy: -300 }],
    }, 0.016).world;
    expect(spawned.drops).toHaveLength(1);
    expect(spawned.paddleWidth).toBe(BREAKER.paddleWidth);
    const falling = advanceBreaker(spawned, 0.016).world;
    expect(falling.drops[0].y).toBeGreaterThan(spawned.drops[0].y);
    const caught = advanceBreaker({
      ...falling,
      drops: [{ ...falling.drops[0], x: falling.paddleX, y: BREAKER.paddleY - 10 }],
    }, 0.016);
    expect(caught.events.caught).toEqual(["wide"]);
    expect(caught.world.paddleWidth).toBeGreaterThan(BREAKER.paddleWidth);
    const missed = advanceBreaker({
      ...falling,
      drops: [{ ...falling.drops[0], x: 10, y: BREAKER.height + 10 }],
    }, 0.016);
    expect(missed.events.caught).toEqual([]);
    expect(missed.world.drops).toHaveLength(0);
    expect(missed.world.paddleWidth).toBe(BREAKER.paddleWidth);
  });

  it("ends after two walls without awarding bonus points", () => {
    const initial = newBreaker();
    const first = hitLastBrick({ ...initial, score: 447 });
    expect(first.events.clearedWave).toBe(true);
    expect(first.world.score).toBe(448);
    expect(first.world.wave).toBe(2);
    expect(first.world.bricks).toHaveLength(112);
    const second = hitLastBrick({ ...first.world, score: 895 });
    expect(second.world.over).toBe(true);
    expect(second.world.won).toBe(true);
    expect(second.world.score).toBe(896);
    expect(second.world.bricks).toHaveLength(0);
  });

  it("launches a bonus olive from the paddle when +1 is caught", () => {
    const world = launchBreakerBall(newBreaker());
    const capsule = { id: 1, kind: "multiball" as const, x: world.paddleX, y: BREAKER.paddleY - 10 };
    const result = advanceBreaker({ ...world, drops: [capsule] }, 0.016);
    expect(result.events.caught).toEqual(["multiball"]);
    expect(result.world.lives).toBe(3);
    expect(result.world.balls).toHaveLength(2);
    const [original, bonus] = result.world.balls;
    expect(bonus.bonus).toBe(true);
    expect(bonus.vy).toBeLessThan(0);
    expect(Math.sign(bonus.vx)).toBe(-Math.sign(original.vx));
    expect(advanceBreaker({ ...result.world, drops: [capsule] }, 0.016).world.balls).toHaveLength(2);
  });

  it("lets both olives hit bricks and only loses a life when neither remains", () => {
    const world = launchBreakerBall(newBreaker());
    const balls = [world.bricks.at(-1)!, world.bricks.at(-3)!].map((brick, i) => ({
      x: brick.x + brick.width / 2,
      y: brick.y + brick.height + 8,
      vx: 0,
      vy: -300,
      bonus: i === 1,
    }));
    const hit = advanceBreaker({ ...world, balls }, 0.016).world;
    expect(hit.score).toBe(2);
    const falling = (ball: typeof balls[number]) => ({ ...ball, y: BREAKER.height + BREAKER.ballRadius, vy: 300 });
    const firstMiss = advanceBreaker({ ...hit, balls: [falling(balls[0]), hit.balls[1]] }, 0.016);
    expect(firstMiss.events.lostLife).toBe(false);
    expect(firstMiss.world.lives).toBe(3);
    expect(firstMiss.world.balls).toHaveLength(1);
    expect(firstMiss.world.balls[0].bonus).toBe(true);
    const lastMiss = advanceBreaker({ ...firstMiss.world, balls: [falling(balls[1])] }, 0.016);
    expect(lastMiss.events.lostLife).toBe(true);
    expect(lastMiss.world.lives).toBe(2);
    expect(lastMiss.world.stuckToPaddle).toBe(true);
  });
});
