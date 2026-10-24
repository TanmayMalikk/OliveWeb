// Contains the olive-flight code for this part of Olive.
export const FLIGHT = {
  width: 400,
  height: 480,
  oliveX: 96,
  radius: 15,
  radiusY: 19,
  branchStroke: 12,
  gap: 174,
  branchWidth: 54,
  speed: 116,
  gravity: 740,
  lift: -270,
};
// Groups the data and actions for branch.
export interface Branch {
  x: number;
  gapY: number;
  gap: number;
  passed: boolean;
  seed?: number;
}
// Groups the data and actions for flight world.
export interface FlightWorld {
  y: number;
  velocity: number;
  score: number;
  branches: Branch[];
  over: boolean;
}
export function flightSpeedMultiplier(score: number) {
  return Math.min(7.5, 1 + Math.floor(score / 2) / 10);
}
export function flightGap(score: number) {
  // The rounded branch tips extend half a stroke into each end of the gap.
  // Keep the visible opening at least two complete olive bodies tall.
  const minimumGap = FLIGHT.radiusY * 4 + FLIGHT.branchStroke;
  return Math.max(minimumGap, FLIGHT.gap - Math.floor(score / 10) * 8);
}
export function newFlight(
  width: number = FLIGHT.width,
  random: () => number = () => 0.5,
): FlightWorld {
  return {
    y: 230,
    velocity: 0,
    score: 0,
    branches: Array.from(
      { length: Math.max(2, Math.ceil(width / 250)) },
      (_, index) => ({
        x: 350 + index * 250,
        gapY: index % 2 ? 195 : 238,
        gap: flightGap(0),
        passed: false,
        seed: random(),
      }),
    ),
    over: false,
  };
}
// Groups the data and actions for point.
type Point = { x: number; y: number };
export const leafPaths = [
  "M0 3 Q-24 8 -25 28 Q-5 26 0 3",
  "M0 9 Q24 12 25 33 Q5 29 0 9",
];
export function branchPath(height: number) {
  return `M27 0 Q17 ${height * 0.36} 29 ${height}`;
}
export function oliveAngle(velocity: number) {
  return Math.max(-25, Math.min(55, velocity / 9));
}
function quadratic(a: Point, control: Point, b: Point, count = 12): Point[] {
  return Array.from({ length: count + 1 }, (_, index) => {
    const t = index / count,
      s = 1 - t;
    return {
      x: s * s * a.x + 2 * s * t * control.x + t * t * b.x,
      y: s * s * a.y + 2 * s * t * control.y + t * t * b.y,
    };
  });
}
const leafOutlines = [
  [
    ...quadratic({ x: 0, y: 3 }, { x: -24, y: 8 }, { x: -25, y: 28 }),
    ...quadratic({ x: -25, y: 28 }, { x: -5, y: 26 }, { x: 0, y: 3 }),
  ],
  [
    ...quadratic({ x: 0, y: 9 }, { x: 24, y: 12 }, { x: 25, y: 33 }),
    ...quadratic({ x: 25, y: 33 }, { x: 5, y: 29 }, { x: 0, y: 9 }),
  ],
];
// Test the rotated olive body, inset by two pixels for forgiving edge contact.
// Its decorative wing and sprig do not count as hits.
function bodyHitsPolygon(points: Point[], oliveY: number, velocity: number) {
  const angle = (oliveAngle(velocity) * Math.PI) / 180;
  const cos = Math.cos(angle),
    sin = Math.sin(angle);
  const local = points.map((p) => {
    const x = p.x - FLIGHT.oliveX,
      y = p.y - oliveY;
    return { x: (x * cos + y * sin) / 13, y: (-x * sin + y * cos) / 17 };
  });
  let inside = false;
  for (let i = 0, j = local.length - 1; i < local.length; j = i++) {
    const a = local[j],
      b = local[i];
    if (a.y > 0 !== b.y > 0 && 0 < a.x + ((b.x - a.x) * -a.y) / (b.y - a.y))
      inside = !inside;
    const dx = b.x - a.x,
      dy = b.y - a.y;
    const length = dx * dx + dy * dy;
    const t = length
      ? Math.max(0, Math.min(1, -(a.x * dx + a.y * dy) / length))
      : 0;
    if ((a.x + t * dx) ** 2 + (a.y + t * dy) ** 2 <= 1) return true;
  }
  return inside;
}
export function hitsLeafyBranch(
  x: number,
  end: number,
  down: boolean,
  oliveY: number,
  velocity: number,
) {
  if (x > FLIGHT.oliveX + 20 || x + 54 < FLIGHT.oliveX - 20) return false;
  const height = down ? end : FLIGHT.height - end;
  const direction = down ? -1 : 1;
  const hit = (points: Point[]) =>
    bodyHitsPolygon(
      points.map((p) => ({ x: x + p.x, y: end + direction * p.y })),
      oliveY,
      velocity,
    );
  // Follow the same quadratic centerline and round 12px stroke as the drawing.
  const stem = quadratic(
    { x: 27, y: 0 },
    { x: 17, y: height * 0.36 },
    { x: 29, y: height },
    24,
  );
  for (let i = 0; i < stem.length; i++) {
    const a = stem[i];
    if (
      hit(
        Array.from({ length: 16 }, (_, n) => ({
          x: a.x + 6 * Math.cos((n * Math.PI) / 8),
          y: a.y + 6 * Math.sin((n * Math.PI) / 8),
        })),
      )
    )
      return true;
    if (i) {
      const b = stem[i - 1],
        length = Math.hypot(a.x - b.x, a.y - b.y);
      const nx = ((a.y - b.y) / length) * 6,
        ny = (-(a.x - b.x) / length) * 6;
      if (
        hit([
          { x: a.x + nx, y: a.y + ny },
          { x: b.x + nx, y: b.y + ny },
          { x: b.x - nx, y: b.y - ny },
          { x: a.x - nx, y: a.y - ny },
        ])
      )
        return true;
    }
  }
  for (let index = 0; index < Math.floor(height / 33); index++) {
    for (const outline of leafOutlines)
      if (hit(outline.map((p) => ({ x: 26 + p.x, y: 20 + index * 33 + p.y }))))
        return true;
  }
  return false;
}
export function advanceFlight(
  world: FlightWorld,
  seconds: number,
  random: () => number = Math.random,
  width: number = FLIGHT.width,
): FlightWorld {
  if (world.over) return world;
  const dt = Math.max(0, Math.min(seconds, 0.032));
  const velocity = world.velocity + FLIGHT.gravity * dt;
  const y = world.y + velocity * dt;
  let score = world.score;
  const speed = FLIGHT.speed * flightSpeedMultiplier(world.score);
  const branches = world.branches
    .map((branch) => {
      const x = branch.x - speed * dt;
      const passed =
        branch.passed || x + FLIGHT.branchWidth < FLIGHT.oliveX - FLIGHT.radius;
      if (passed && !branch.passed) score++;
      return { ...branch, x, passed };
    })
    .filter((branch) => branch.x + FLIGHT.branchWidth > -10);
  while (branches.length < Math.max(2, Math.ceil(width / 250)))
    branches.push({
      x: branches.length ? branches[branches.length - 1].x + 250 : width + 40,
      gapY: 155 + random() * 165,
      gap: flightGap(score),
      passed: false,
      seed: random(),
    });
  const angle = (oliveAngle(velocity) * Math.PI) / 180;
  const verticalRadius = Math.hypot(13 * Math.sin(angle), 17 * Math.cos(angle));
  const over =
    y - verticalRadius <= 0 ||
    y + verticalRadius >= FLIGHT.height ||
    branches.some(
      (b) =>
        hitsLeafyBranch(b.x, b.gapY - b.gap / 2, true, y, velocity) ||
        hitsLeafyBranch(b.x, b.gapY + b.gap / 2, false, y, velocity),
    );
  return { y, velocity, score, branches, over };
}
