// Cover users: physics and basket-catching logic for Orchard Catch.
export const ORCHARD = {
  width: 640,
  height: 460,
  basketY: 404,
  basketWidth: 96,
  basketHeight: 28,
  itemRadius: 15,
};

export type OrchardItemKind = "olive" | "fig" | "blossom" | "pinecone";

export interface OrchardItem {
  id: number;
  x: number;
  y: number;
  vy: number;
  vx: number;
  rotation: number;
  kind: OrchardItemKind;
}

export interface OrchardPopup {
  id: number;
  x: number;
  y: number;
  text: string;
  positive: boolean;
  life: number;
}

export interface OrchardWorld {
  basketX: number;
  items: OrchardItem[];
  popups: OrchardPopup[];
  score: number;
  streak: number;
  lives: number;
  wave: number;
  spawnIn: number;
  nextId: number;
  over: boolean;
}

const validRandom = (random: () => number) => {
  const value = random();
  return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0.5;
};

export function newOrchard(): OrchardWorld {
  return {
    basketX: ORCHARD.width / 2,
    items: [],
    popups: [],
    score: 0,
    streak: 0,
    lives: 3,
    wave: 1,
    spawnIn: 0.35,
    nextId: 1,
    over: false,
  };
}

export function moveOrchardBasket(
  world: OrchardWorld,
  targetX: number,
): OrchardWorld {
  if (!Number.isFinite(targetX) || world.over) return world;
  const half = ORCHARD.basketWidth / 2;
  const clamped = Math.max(half + 10, Math.min(ORCHARD.width - half - 10, targetX));
  return { ...world, basketX: clamped };
}

export interface OrchardStepEvents {
  caught: OrchardItemKind | null;
  missed: boolean;
}

export function advanceOrchard(
  world: OrchardWorld,
  seconds: number,
  random: () => number = Math.random,
): { world: OrchardWorld; events: OrchardStepEvents } {
  const events: OrchardStepEvents = { caught: null, missed: false };
  if (world.over || !Number.isFinite(seconds) || seconds <= 0) {
    return { world, events };
  }

  const dt = Math.min(seconds, 0.04);
  let score = world.score;
  let streak = world.streak;
  let lives = world.lives;
  let nextId = world.nextId;
  let spawnIn = world.spawnIn - dt;
  const wave = 1 + Math.floor(score / 150);

  const items: OrchardItem[] = [];
  let popups = world.popups
    .map((p) => ({ ...p, y: p.y - 36 * dt, life: p.life - dt }))
    .filter((p) => p.life > 0);

  const halfBasket = ORCHARD.basketWidth / 2;

  for (const item of world.items) {
    const ny = item.y + item.vy * dt;
    const nx = Math.max(
      20,
      Math.min(ORCHARD.width - 20, item.x + item.vx * dt),
    );
    const inBasketY =
      ny + ORCHARD.itemRadius >= ORCHARD.basketY &&
      ny - ORCHARD.itemRadius <= ORCHARD.basketY + ORCHARD.basketHeight;
    const inBasketX = Math.abs(nx - world.basketX) <= halfBasket + 8;

    if (inBasketY && inBasketX) {
      events.caught = item.kind;
      if (item.kind === "pinecone") {
        lives -= 1;
        streak = 0;
        events.missed = true;
        popups.push({
          id: ++nextId,
          x: nx,
          y: ORCHARD.basketY - 12,
          text: "-1 leaf",
          positive: false,
          life: 0.75,
        });
      } else {
        streak += 1;
        const multiplier = Math.min(4, 1 + Math.floor(streak / 4));
        const base =
          item.kind === "blossom" ? 30 : item.kind === "fig" ? 20 : 10;
        const gained = base * multiplier;
        score += gained;
        if (item.kind === "blossom" && lives < 3) lives += 1;
        popups.push({
          id: ++nextId,
          x: nx,
          y: ORCHARD.basketY - 12,
          text: multiplier > 1 ? `+${gained} (${multiplier}×)` : `+${gained}`,
          positive: true,
          life: 0.75,
        });
      }
      continue;
    }

    if (ny - ORCHARD.itemRadius > ORCHARD.height) {
      if (item.kind !== "pinecone") {
        streak = 0;
        lives -= 1;
        events.missed = true;
      }
      continue;
    }

    items.push({
      ...item,
      x: nx,
      y: ny,
      rotation: item.rotation + dt * (item.kind === "pinecone" ? 2.4 : 0.9),
    });
  }

  if (spawnIn <= 0 && lives > 0) {
    const roll = validRandom(random);
    const kind: OrchardItemKind =
      roll < 0.18 + Math.min(0.14, (wave - 1) * 0.025)
        ? "pinecone"
        : roll > 0.91
          ? "blossom"
          : roll > 0.7
            ? "fig"
            : "olive";
    items.push({
      id: ++nextId,
      x: 34 + validRandom(random) * (ORCHARD.width - 68),
      y: -18,
      vy: 145 + validRandom(random) * 65 + Math.min(140, (wave - 1) * 20),
      vx: (validRandom(random) - 0.5) * 32,
      rotation: validRandom(random) * Math.PI,
      kind,
    });
    spawnIn = Math.max(0.42, 1.02 - (wave - 1) * 0.065) * (0.75 + validRandom(random) * 0.5);
  }

  return {
    world: {
      basketX: world.basketX,
      items,
      popups: popups.slice(-8),
      score,
      streak,
      lives: Math.max(0, lives),
      wave,
      spawnIn,
      nextId,
      over: lives <= 0,
    },
    events,
  };
}
