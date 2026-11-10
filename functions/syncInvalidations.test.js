// Tests content-free sync audiences and prevents chat messages from creating a second fetch pipeline.
const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId: "olive-test",
  storageBucket: "olive-test.appspot.com",
  databaseURL: "https://olive-test.firebaseio.com",
});
const sync = require("./src/syncInvalidations")._test;

test("safety invalidations include previous and new authorized roles", () => {
  assert.deepEqual(sync.safetyAudience(
      {ownerUid:"owner", staffUid:"old-staff", safeContactUids:["safe-a"]},
      {ownerUid:"owner", staffUid:"new-staff", adminUid:"admin", safeContactUids:["safe-b"]},
  ).sort(), ["admin", "new-staff", "old-staff", "owner", "safe-a", "safe-b"]);
});

test("media revocation invalidates both removed and retained viewers", () => {
  assert.deepEqual(sync.mediaAudience(
      {owner_id:"owner", allowed_users:["owner", "revoked"]},
      {owner_id:"owner", allowed_users:["owner", "retained"]},
  ).sort(), ["owner", "retained", "revoked"]);
});

test("ordinary chat message markers do not trigger conversation reconciliation", () => {
  const before = {
    participantIds:["owner", "staff"], roomKeys:{owner:"a", staff:"b"},
    participantPublicKeys:{owner:"oa", staff:"sb"}, ownerUid:"owner", staffUid:"staff",
    lastMessageId:"one", updatedAt:1,
  };
  assert.equal(sync.conversationAccessChanged(before, {...before, lastMessageId:"two", updatedAt:2}), false);
  assert.equal(sync.conversationAccessChanged(before, {...before, participantIds:["owner"]}), true);
  assert.equal(sync.conversationAccessChanged(before, {...before, roomKeys:{owner:"new", staff:"b"}}), true);
});

test("audience parsing rejects malformed and oversized identifiers under repetition", () => {
  for (let iteration = 0; iteration < 1000; iteration += 1) {
    assert.deepEqual(sync.strings(["valid", "valid", "", 42, null, "x".repeat(129)]), ["valid"]);
  }
});
