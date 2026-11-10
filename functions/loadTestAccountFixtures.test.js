// Load-test account fixtures: confirms the manual test set is complete, unique, and fictional.
const test = require("node:test");
const assert = require("node:assert/strict");
const {
  accounts,
  communities,
  COUNTS,
  TOTAL_ACCOUNTS,
  triggerHash,
} = require("./scripts/load-test-account-fixtures");

test("load-test fixtures contain the requested unique Alberta and British Columbia profiles", () => {
  const count = role => accounts.filter(account => account.role === role).length;
  assert.equal(count("USER"), COUNTS.USER);
  assert.equal(count("STAFF"), COUNTS.STAFF);
  assert.equal(count("SAFE_CONTACT"), COUNTS.SAFE_CONTACT);
  assert.equal(accounts.length, TOTAL_ACCOUNTS);
  assert.equal(new Set(accounts.map(account => account.username)).size, TOTAL_ACCOUNTS);
  assert.equal(new Set(accounts.map(account => account.fullName)).size, TOTAL_ACCOUNTS);
  assert.equal(new Set(accounts.map(account => account.address.line1)).size, TOTAL_ACCOUNTS);
  assert.ok(accounts.every(account => account.address.country === "Canada"));
  assert.deepEqual(new Set(accounts.map(account => account.address.province)),
      new Set(["Alberta", "British Columbia"]));
  assert.equal(new Set(accounts.map(account => `${account.latitude},${account.longitude}`)).size,
      TOTAL_ACCOUNTS);
  assert.ok(accounts.every(account => account.latitude >= 48 && account.latitude <= 58));
  assert.ok(accounts.every(account => account.longitude >= -126 && account.longitude <= -109.9));
  assert.match(triggerHash, /^[a-f0-9]{64}$/);
});

test("staff fixtures have complete organization profiles and unique login aliases", () => {
  const staff = accounts.filter(account => account.role === "STAFF");
  assert.equal(new Set(staff.map(account => account.email)).size, COUNTS.STAFF);
  assert.ok(staff.every(account => account.email === `${account.username}@yourolivebranch.ca`));
  assert.ok(staff.every(account => account.fullName && account.dob && account.phone));
});

test("every fixture uses a randomized point away from its listed city centre", () => {
  const centres = new Map(communities.map(([city, province, latitude, longitude]) =>
    [`${city}, ${province}`, {latitude, longitude}]));
  accounts.forEach(account => {
    const centre = centres.get(account.region);
    assert.ok(centre, `Missing community centre for ${account.region}`);
    const latitudeMetres = (account.latitude - centre.latitude) * 110_574;
    const longitudeMetres = (account.longitude - centre.longitude) *
      111_320 * Math.cos(centre.latitude * Math.PI / 180);
    const distance = Math.hypot(latitudeMetres, longitudeMetres);
    assert.ok(distance >= 590 && distance <= 1_910,
        `${account.username} must stay off-centre and inside its community test radius`);
  });
});
