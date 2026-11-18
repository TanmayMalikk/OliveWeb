// Tests catching, streaks, and life tracking for Orchard Catch.
import { describe, expect, it } from "vitest";
import {
  ORCHARD,
  advanceOrchard,
  moveOrchardBasket,
  newOrchard,
} from "./orchard-catch";

describe("Orchard Catch mechanics", () => {
  it("clamps basket inside the grove bounds", () => {
    const start = newOrchard();
    const left = moveOrchardBasket(start, -100);
    expect(left.basketX).toBeGreaterThanOrEqual(ORCHARD.basketWidth / 2 + 10);
    const right = moveOrchardBasket(start, ORCHARD.width + 100);
    expect(right.basketX).toBeLessThanOrEqual(ORCHARD.width - ORCHARD.basketWidth / 2 - 10);
  });

  it("rewards catching olives and deducts a leaf for pinecones", () => {
    const base = newOrchard();
    const catchingOlive = {
      ...base,
      items: [
        {
          id: 1,
          x: base.basketX,
          y: ORCHARD.basketY,
          vy: 150,
          vx: 0,
          rotation: 0,
          kind: "olive" as const,
        },
      ],
    };
    const caught = advanceOrchard(catchingOlive, 0.02, () => 0.4);
    expect(caught.events.caught).toBe("olive");
    expect(caught.world.score).toBe(10);
    expect(caught.world.streak).toBe(1);

    const catchingCone = {
      ...caught.world,
      items: [
        {
          id: 2,
          x: caught.world.basketX,
          y: ORCHARD.basketY,
          vy: 150,
          vx: 0,
          rotation: 0,
          kind: "pinecone" as const,
        },
      ],
    };
    const hurt = advanceOrchard(catchingCone, 0.02, () => 0.4);
    expect(hurt.events.missed).toBe(true);
    expect(hurt.world.lives).toBe(2);
    expect(hurt.world.streak).toBe(0);
  });
});
