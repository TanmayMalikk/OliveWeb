// Tests content-free sync audiences and prevents chat messages from creating a second fetch pipeline.
const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId: "olive-test",
  storageBucket: "olive-test.appspot.com",
  databaseURL: "https://olive-test.firebaseio.com",
});
const {db, messaging} = require("./src/firebaseAdminClient");
const {syncAccountInvalidation, _test: sync} = require("./src/syncInvalidations");

test("safety invalidations include previous and new authorized roles", () => {
  assert.deepEqual(sync.safetyAudience(
      {ownerUid:"owner", staffUid:"old-staff", safeContactUids:["safe-a"]},
      {ownerUid:"owner", staffUid:"new-staff", adminUid:"admin", safeContactUids:["safe-b"]},
  ).sort(), ["admin", "new-staff", "old-staff", "owner", "safe-a", "safe-b"]);
});

test("only safety access changes are urgent", () => {
  const before = {ownerUid:"owner", staffUid:"staff", safeContactUids:["safe"], fields:{a:1}};
  assert.equal(sync.safetyAccessChanged(before, {...before, fields:{a:2}, updatedAt:2}), false);
  assert.equal(sync.safetyAccessChanged(before, {...before, staffUid:"new-staff"}), true);
  assert.equal(sync.safetyAccessChanged(before, {...before, safeContactUids:[]}), true);
});

test("media revocation invalidates both removed and retained viewers", () => {
  assert.deepEqual(sync.mediaAudience(
      {owner_id:"owner", allowed_users:["owner", "revoked"]},
      {owner_id:"owner", allowed_users:["owner", "retained"]},
  ).sort(), ["owner", "retained", "revoked"]);
  assert.deepEqual(sync.revokedMediaUids(
      {allowed_users:["owner", "revoked"]}, {allowed_users:["owner", "retained"]}),
  ["revoked"]);
  assert.deepEqual(sync.revokedMediaUids(
      {allowed_users:["owner", "revoked"]}, {}), ["owner", "revoked"]);
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

test("account changes write a revision and wake devices registered for app notifications", async (t) => {
  const marker = {};
  const deviceRef = {};
  const sent = [];
  t.mock.method(db, "collection", (name) => {
    if (name === "syncInvalidations") return {doc: (uid) => {
      assert.equal(uid, "staff");
      return marker;
    }};
    assert.equal(name, "notificationTokens");
    return {doc: (uid) => {
      assert.equal(uid, "staff");
      return {collection: (subcollection) => {
        assert.equal(subcollection, "devices");
        return {get: async () => ({docs: [{ref: deviceRef, get: () => "staff-device-token"}]})};
      }};
    }};
  });
  t.mock.method(db, "runTransaction", async (callback) => callback({
    get: async (ref) => {
      assert.equal(ref, marker);
      return {data: () => ({revision: 4, accountRevision: 1})};
    },
    set: (ref, value) => {
      assert.equal(ref, marker);
      assert.equal(value.accountRevision, 2);
      assert.deepEqual(value.allowedUsers, ["staff"]);
    },
  }));
  t.mock.method(messaging, "sendEachForMulticast", async (message) => {
    sent.push(message);
    return {responses: [{success: true}]};
  });

  await syncAccountInvalidation.run({
    data: {before: {exists: false}, after: {exists: true, data: () => ({uid: "staff"})}},
  });

  assert.deepEqual(sent, [{
    tokens: ["staff-device-token"],
    data: {type: "sync_invalidation", revision: "5", urgent: "1"},
    android: {priority: "normal"},
  }]);
});
