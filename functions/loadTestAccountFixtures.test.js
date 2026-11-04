// Load-test account fixtures: confirms the manual test set is complete, unique, and fictional.
const test = require("node:test");
const assert = require("node:assert/strict");
const {accounts, triggerHash} = require("./scripts/load-test-account-fixtures");

test("load-test fixtures contain the requested roles and unique Alberta profiles", () => {
  const count = role => accounts.filter(account => account.role === role).length;
  assert.equal(count("USER"), 40);
  assert.equal(count("STAFF"), 8);
  assert.equal(count("SAFE_CONTACT"), 5);
  assert.equal(new Set(accounts.map(account => account.username)).size, 53);
  assert.equal(new Set(accounts.map(account => account.fullName)).size, 53);
  assert.equal(new Set(accounts.map(account => account.address.line1)).size, 53);
  assert.equal(new Set(accounts.map(account => account.region)).size, 53);
  assert.ok(accounts.every(account => account.address.country === "Canada"));
  assert.ok(accounts.every(account => account.address.province === "Alberta"));
  assert.ok(accounts.every(account => account.region.endsWith(", Alberta")));
  assert.equal(new Set(accounts.map(account => `${account.latitude},${account.longitude}`)).size, 53);
  assert.ok(accounts.every(account => account.latitude >= 49 && account.latitude <= 60));
  assert.ok(accounts.every(account => account.longitude >= -120 && account.longitude <= -110));
  assert.match(triggerHash, /^[a-f0-9]{64}$/);
});

test("staff fixtures have complete organization profiles and unique login aliases", () => {
  const staff = accounts.filter(account => account.role === "STAFF");
  assert.equal(new Set(staff.map(account => account.email)).size, 8);
  assert.ok(staff.every(account => account.email === `${account.username}@yourolivebranch.ca`));
  assert.ok(staff.every(account => account.fullName && account.dob && account.phone));
});
