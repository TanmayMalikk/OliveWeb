// Location simulation: checks that test routes loop safely and move at the requested speed.
const test = require("node:test");
const assert = require("node:assert/strict");
const {
  distanceMetres,
  positionAt60Kmph,
  routeLoop,
  METRES_PER_SECOND_AT_60_KMPH,
} = require("./scripts/load-test-location-simulation");

test("a generated Alberta movement route is a closed loop around its seeded point", () => {
  const route = routeLoop(53.5461, -113.4938);
  assert.equal(route.length, 17);
  assert.deepEqual(route[0], route.at(-1));
  assert.ok(route.every(point => point.latitude >= 49 && point.latitude <= 60));
  assert.ok(route.every(point => point.longitude >= -120 && point.longitude <= -110));
});

test("the movement calculation travels approximately 60 kilometres per hour", () => {
  const route = routeLoop(53.5461, -113.4938);
  const start = positionAt60Kmph(route, 0);
  const afterOneSecond = positionAt60Kmph(route, 1_000);
  const travelled = distanceMetres(start, afterOneSecond);
  assert.ok(Math.abs(travelled - METRES_PER_SECOND_AT_60_KMPH) < 0.2,
      `Expected about ${METRES_PER_SECOND_AT_60_KMPH} m but moved ${travelled} m.`);
});

test("invalid route inputs fail instead of producing broken map coordinates", () => {
  assert.throws(() => routeLoop("Edmonton", -113.4938), TypeError);
  assert.throws(() => routeLoop(53.5461, -113.4938, 0), RangeError);
  assert.throws(() => positionAt60Kmph([], 1_000), TypeError);
});
