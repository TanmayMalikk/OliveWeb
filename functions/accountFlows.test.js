const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
const data = {username: "fixture_user", role: "USER", password: "Test-password1!"};
test("public account creation normalizes names, sets trusted claims and prevents cross-role duplicates", async () => {
  const f = fixture(), api = f.load("publicAccounts");
  const created = await api.createPublicAccount({data: {...data, username: " FIXTURE_USER "}});
  assert.equal(created.username, "fixture_user");
  assert.equal(f.users.get(created.uid).customClaims.role, "USER");
  assert.equal(f.users.get(created.uid).emailVerified, true);
  await assert.rejects(api.createPublicAccount({data: {...data, role: "SAFE_CONTACT"}}), {code: "already-exists"});
  for (const role of ["STAFF", "ADMIN"]) await assert.rejects(api.createPublicAccount({data: {...data, username: "different_name", role}}), {code: "permission-denied"});
  await assert.rejects(api.createPublicAccount({data: {...data, password: "weak"}}), {code: "invalid-argument"});
});
test("failed public creation rolls back only the account made by that operation", async () => {
  const f = fixture(), api = f.load("publicAccounts");
  f.services["./firebaseAdminClient"].auth.setCustomUserClaims = async () => {throw new Error("claims failed");};
  await assert.rejects(api.createPublicAccount({data}), /claims failed/);
  assert.equal(f.users.size, 0); assert.equal(f.records.has("accountDirectory/fixture_user"), false);
});
test("admins create organization accounts with aliases and roll back failed profile writes", async () => {
  const f = fixture(), api = f.load("adminAccounts"); f.setCaller({uid: "admin", role: "ADMIN"});
  const created = await api.adminCreateAccount({data: {...data, role: "STAFF", email: "fixture@example.invalid", fullName: "Test Staff", dob: "1990-01-01"}});
  assert.equal(created.email, "fixture@example.invalid");
  assert.equal(f.users.get(created.uid).customClaims.role, "STAFF");
  assert.equal(f.records.get("accountDirectory/fixture_user").loginAlias, "fixture@example.invalid");
  const broken = fixture(); broken.setCaller({uid: "admin", role: "ADMIN"});
  broken.services["./accountStorage"].writeProfile = async () => {throw new Error("storage unavailable");};
  await assert.rejects(broken.load("adminAccounts").adminCreateAccount({data: {...data, username: "another_fixture"}}), /storage unavailable/);
  assert.equal(broken.users.size, 0);
  assert.equal(f.users.size, 1);
  f.setCaller({uid: "user", role: "USER"});
  await assert.rejects(api.adminCreateAccount({data}), {code: "permission-denied"});
});
test("admin summaries and workloads count active and completed tickets without downloading profile files", async () => {
  const f = fixture(), api = f.load("adminAccounts"); f.setCaller({uid: "admin", role: "ADMIN"});
  for (const [uid, role] of [["admin", "ADMIN"], ["staff", "STAFF"], ["user", "USER"]]) {
    f.users.set(uid, {uid, customClaims: {role}}); f.records.set(`accountDirectory/${uid}`, {uid, role, username: uid, profileComplete: true});
  }
  f.records.set("conversations/open", {type: "SUPPORT", staffUid: "staff"});
  f.records.set("conversations/closed", {type: "SUPPORT", staffUid: "staff", supportOrigin: "PLAN_CONSULT", ticketStatus: "COMPLETED"});
  f.records.set("safetyStaff/staff", {activeCount: 1});
  const summary = await api.adminListAccounts({auth: {uid: "admin"}, data: {view: "SUMMARY"}});
  assert.equal(summary.counts.USER, 1); assert.deepEqual(summary.tickets, {open: 1, closed: 1});
  const rows = (await api.adminListAccounts({auth: {uid: "admin"}, data: {view: "STAFF"}})).accounts;
  assert.equal(rows.length, 1); assert.equal(rows[0].assignedUsers, 1); assert.equal(rows[0].supportChats, 1); assert.equal(rows[0].completedSafetyPlans, 1);
  const accounts = (await api.adminListAccounts({auth: {uid: "admin"}, data: {}})).accounts;
  assert.equal(accounts.find(row => row.uid === "admin").protected, true);
});
test("organization setup preserves protected login aliases while updating contact information", async () => {
  const f = fixture(), api = f.load("organizationAccounts");
  f.users.set("staff", {uid: "staff", email: "old@example.invalid", displayName: "staff_name", customClaims: {role: "STAFF", extra: true}});
  f.profiles.set("staff", {username: "staff_name", role: "STAFF", email: "old@example.invalid"});
  const request = {auth: {uid: "staff"}, data: {fullName: "Test Staff", dob: "1990-01-01", email: "new@example.invalid", phone: "555"}};
  const response = await api.completeOrganizationProfile(request);
  assert.equal(response.profile.email, "new@example.invalid");
  assert.equal(f.users.get("staff").email, "old@example.invalid");
  assert.equal(f.users.get("staff").customClaims.extra, true);
  await assert.rejects(api.completeOrganizationProfile({data: request.data}), {code: "unauthenticated"});
  await assert.rejects(api.completeOrganizationProfile({...request, data: {...request.data, dob: "invalid"}}), {code: "invalid-argument"});
  assert.equal((await api.restoreOrganizationLoginAlias({auth: {uid: "staff"}})).changed, true);
  assert.equal((await api.restoreOrganizationLoginAlias({auth: {uid: "staff"}})).changed, false);
});
test("organization aliases resolve only for the requested role and reject invalid identifiers", async () => {
  const f = fixture(), api = f.load("organizationAccounts");
  f.records.set("accountDirectory/staff_name", {uid: "staff", role: "STAFF"});
  assert.equal((await api.resolveOrganizationLogin({data: {identifier: "staff_name", role: "STAFF"}})).authEmail, "staff_name@staff.olive.invalid");
  await assert.rejects(api.resolveOrganizationLogin({data: {identifier: "staff_name", role: "ADMIN"}}), {code: "not-found"});
  await assert.rejects(api.resolveOrganizationLogin({data: {identifier: "staff_name", role: "USER"}}), {code: "invalid-argument"});
  await assert.rejects(api.resolveOrganizationLogin({data: {identifier: "bad@", role: "STAFF"}}), {code: "invalid-argument"});
  await assert.rejects(api.restoreOrganizationLoginAlias({}), {code: "unauthenticated"});
});

test("deleting an ordinary account cleans nested records and incoming references without touching another account", async () => {
  const f = fixture(), api = f.load("accountDeletion");
  f.setCaller({uid: "admin", role: "ADMIN"});
  f.users.set("target", {uid: "target", displayName: "fixture_user", customClaims: {role: "USER"}});
  for (const [key, value] of [
    ["safetyPlans/target", {ownerUid: "target"}], ["users/target", {}],
    ["friendLists/other/friends/friendship", {friendUid: "target"}],
    ["friendRequests/request", {senderUid: "target", recipientUid: "other"}],
    ["conversations/personal", {participantIds: ["target", "other"]}],
    ["privateEntries/owned", {ownerUid: "target", allowedUsers: ["target"]}],
    ["accountDirectory/fixture_user", {uid: "target"}],
    ["safetyPlans/unrelated", {ownerUid: "unrelated"}],
  ]) f.records.set(key, value);
  await api.adminDeleteAccount({auth: {uid: "admin"}, data: {uid: "target"}});
  assert.equal(f.users.has("target"), false);
  for (const key of ["safetyPlans/target", "friendLists/other/friends/friendship", "friendRequests/request", "conversations/personal", "privateEntries/owned", "accountDirectory/fixture_user"]) assert.equal(f.records.has(key), false, key);
  assert.equal(f.records.has("safetyPlans/unrelated"), true);
});
test("admins cannot delete their own or protected accounts", async () => {
  const f = fixture(), api = f.load("accountDeletion"); f.setCaller({uid: "admin", role: "ADMIN"});
  await assert.rejects(api.adminDeleteAccount({data: {uid: "admin"}}), {code: "failed-precondition"});
  f.users.set("protected", {uid: "protected", displayName: "demoAdmin", customClaims: {role: "ADMIN", protected: true}});
  await assert.rejects(api.adminDeleteAccount({data: {uid: "protected"}}), {code: "permission-denied"});
  assert.equal(f.users.has("protected"), true);
});

test("removing an admin keeps the consultation and restores its staff conversation", async () => {
  const restored = [];
  const f = fixture({
    "./supportGroups": {
      PLAN_CONSULT: "PLAN_CONSULT",
      saveSupportConversation: async (plan, safeContactUid) => restored.push({plan, safeContactUid}),
    },
  });
  const api = f.load("accountDeletion");
  f.setCaller({uid: "acting-admin", role: "ADMIN"});
  const request = {auth: {uid: "acting-admin"}, data: {uid: "departing-admin"}};
  f.users.set("departing-admin", {
    uid: "departing-admin", displayName: "departing_admin", customClaims: {role: "ADMIN"},
  });
  await assert.rejects(api.adminDeleteAccount(request), {code: "failed-precondition"});
  assert.equal(f.users.has("departing-admin"), true);

  f.users.set("acting-admin", {uid: "acting-admin", customClaims: {role: "ADMIN"}});
  f.records.set("safetyPlans/owner", {
    ownerUid: "owner", staffUid: "staff", adminUid: "departing-admin", adminName: "Departing admin",
    consultationConversationId: "consult-room", supportSafeContactUid: "trusted-contact",
  });
  assert.deepEqual(await api.adminDeleteAccount(request), {deleted: true, uid: "departing-admin"});
  assert.equal(f.records.get("safetyPlans/owner").adminUid, "");
  assert.equal(f.records.get("safetyPlans/owner").adminName, "");
  assert.equal(restored.length, 1);
  assert.equal(restored[0].plan.conversationId, "consult-room");
  assert.equal(restored[0].plan.supportOrigin, "PLAN_CONSULT");
  assert.equal(restored[0].plan.staffUid, "staff");
  assert.equal(restored[0].safeContactUid, "trusted-contact");
  assert.equal(f.users.has("acting-admin"), true);
});

test("deleting an account queries many friend lists in parallel batches instead of sequentially", async () => {
  const f = fixture();
  f.setCaller({uid: "admin", role: "ADMIN"});
  f.users.set("target", {uid: "target", displayName: "fixture_user", customClaims: {role: "USER"}});
  for (let i = 0; i < 40; i++) {
    const friendUid = i % 2 === 0 ? "target" : "other";
    f.records.set(`friendLists/owner-${i}/friends/f-${i}`, {friendUid});
  }
  let activeFriendQueries = 0;
  let peakConcurrentFriendQueries = 0;
  const wrapFriendRef = (ref) => {
    const wrapQuery = (q) => ({
      ...q,
      where: (...args) => wrapQuery(q.where(...args)),
      get: async () => {
        activeFriendQueries += 1;
        peakConcurrentFriendQueries = Math.max(peakConcurrentFriendQueries, activeFriendQueries);
        await new Promise((resolve) => setTimeout(resolve, 10));
        try {
          return await q.get();
        } finally {
          activeFriendQueries -= 1;
        }
      },
    });
    return {
      ...ref,
      collection: (sub) => (sub === "friends" ? wrapQuery(ref.collection(sub)) : ref.collection(sub)),
    };
  };
  const originalCollection = f.db.collection.bind(f.db);
  f.db.collection = (name) => {
    const col = originalCollection(name);
    if (name !== "friendLists") return col;
    return {
      ...col,
      listDocuments: async () => (await col.listDocuments()).map(wrapFriendRef),
    };
  };

  const api = f.load("accountDeletion");
  await api.adminDeleteAccount({auth: {uid: "admin"}, data: {uid: "target"}});

  assert.equal(f.users.has("target"), false);
  for (let i = 0; i < 40; i++) {
    const key = `friendLists/owner-${i}/friends/f-${i}`;
    assert.equal(f.records.has(key), i % 2 !== 0, key);
  }
  assert.ok(
    peakConcurrentFriendQueries > 1,
    `Expected batched concurrent friend queries (> 1), saw peak=${peakConcurrentFriendQueries}`,
  );
});
