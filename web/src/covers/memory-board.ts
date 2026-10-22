export function createMemoryBoard(size: number): number[] {
  if (!Number.isInteger(size) || size < 5 || size > 8)
    throw new Error("Board size must be between 5 and 8.");
  const pairCount = Math.floor((size * size) / 2);
  const cards = Array.from(
    { length: pairCount * 2 },
    (_, index) => index % pairCount,
  );
  for (let i = cards.length - 1; i > 0; i--) {
    const j = crypto.getRandomValues(new Uint32Array(1))[0] % (i + 1);
    [cards[i], cards[j]] = [cards[j], cards[i]];
  }
  if (size % 2) cards.splice(Math.floor((size * size) / 2), 0, -1);
  return cards;
}
