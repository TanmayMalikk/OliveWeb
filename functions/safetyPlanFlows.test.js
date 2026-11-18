const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
function prepared() {
  const f = fixture();
  return {...f, api: f.load("safetyPlans")};
}
const changes = (value, revision, mutationId) => [{key: "primary_destination", value, revision, mutationId}];
test("owner initializes, retries and updates a plan while an unshared account is denied", async () => {
  const f = prepared();
  let response = await f.api.safetyPlanSync({data: {ownerUid: "owner", defaults: {primary_destination: "Initial"}}});
  assert.equal(response.plan.fields.primary_destination.value, "Initial");
  response = await f.api.safetyPlanSync({data: {ownerUid: "owner", changes: changes("Updated", 1, "edit-1")}});
  assert.equal(response.plan.fields.primary_destination.value, "Updated");
  assert.equal(response.plan.fields.primary_destination.revision, 2);
  await f.api.safetyPlanSync({data: {ownerUid: "owner", changes: changes("Updated", 1, "edit-1")}});
  assert.equal(f.records.get("safetyPlans/owner").fields.primary_destination.revision, 2);
  f.setCaller({uid: "outsider", role: "USER"});
  await assert.rejects(f.api.safetyPlanSync({data: {ownerUid: "owner"}}), {code: "permission-denied"});
});
for (const useIncoming of [true, false]) test(`owner resolves a staff conflict with ${useIncoming ? "incoming" : "existing"} value and stale resolutions abort`, async () => {
  const f = prepared();
  f.records.set("safetyPlans/owner", {ownerUid: "owner", staffUid: "staff", consultationRequested: true, fields: {primary_destination: {value: "Owner update", revision: 2, mutationId: "owner-edit"}}});
  f.setCaller({uid: "staff", role: "STAFF"});
  const result = await f.api.safetyPlanSync({data: {ownerUid: "owner", changes: changes("Staff update", 1, "staff-edit")}});
  assert.ok(result.conflicts.includes("primary_destination"));
  const resolution = {key: "primary_destination", mutationId: "staff-edit", revision: 2, useIncoming};
  await assert.rejects(f.api.safetyPlanSync({data: {ownerUid: "owner", resolution}}), {code: "permission-denied"});
  f.setCaller({uid: "owner", role: "USER"});
  await assert.rejects(f.api.safetyPlanSync({data: {ownerUid: "owner", resolution: {...resolution, revision: 1}}}), {code: "aborted"});
  const resolved = await f.api.safetyPlanSync({data: {ownerUid: "owner", resolution}});
  assert.equal(resolved.plan.fields.primary_destination.value, useIncoming ? "Staff update" : "Owner update");
  assert.deepEqual(resolved.plan.pendingConflicts, {});
  assert.deepEqual(resolved.plan.resolvedConflicts.primary_destination, ["staff-edit"]);
});
test("safe contacts have read-only access and cannot submit plan edits", async () => {
  const f = prepared(); f.records.set("safetyPlans/owner", {ownerUid: "owner", safeContactUids: ["friend"], fields: {}});
  f.setCaller({uid: "friend", role: "SAFE_CONTACT"});
  await f.api.safetyPlanSync({data: {ownerUid: "owner"}});
  await assert.rejects(f.api.safetyPlanSync({data: {ownerUid: "owner", changes: changes("malicious", 0, "change")}}), {code: "permission-denied"});
});
test("plan sharing checks friendship, trusted role and enabled status and supports revocation", async () => {
  const f = prepared();
  f.records.set("safetyPlans/owner", {ownerUid: "owner", fields: {}});
  const contacts = [{uid: "friend", friendshipId: "accepted", name: " Trusted friend "}];
  await assert.rejects(f.api.safetyPlanShareContacts({data: {contacts}}), {code: "permission-denied"});
  f.records.set("friendLists/owner/friends/accepted", {friendUid: "friend"});
  f.users.set("friend", {uid: "friend", disabled: true, customClaims: {role: "SAFE_CONTACT"}});
  await assert.rejects(f.api.safetyPlanShareContacts({data: {contacts}}), {code: "permission-denied"});
  f.users.get("friend").disabled = false;
  const shared = await f.api.safetyPlanShareContacts({data: {contacts}});
  assert.deepEqual(shared.plan.safeContactUids, ["friend"]);
  assert.equal(shared.plan.safeContactNames.friend, "Trusted friend");
  assert.deepEqual((await f.api.safetyPlanShareContacts({data: {contacts: []}})).plan.safeContactUids, []);
  await assert.rejects(f.api.safetyPlanShareContacts({data: {contacts: [...contacts, ...contacts]}}), {code: "invalid-argument"});
});
test("attachment recipients are deduplicated and filtered to valid public usernames", async () => {
  const f = prepared();
  await assert.rejects(f.api.safetyPlanMediaRecipients({}), {code: "failed-precondition"});
  f.records.set("safetyPlans/owner", {safeContactUids: ["friend", "friend", "bad"], staffUid: "staff"});
  f.profiles.set("friend", {username: "FRIEND_NAME"}); f.profiles.set("staff", {username: "staff_name"}); f.profiles.set("bad", {username: "bad!"});
  assert.deepEqual((await f.api.safetyPlanMediaRecipients({})).recipients, [{uid: "friend", username: "friend_name"}, {uid: "staff", username: "staff_name"}]);
  f.setCaller({uid: "staff", role: "STAFF"});
  await assert.rejects(f.api.safetyPlanMediaRecipients({}), {code: "permission-denied"});
});
test("safe-contact list returns only shared plans and staff availability is role restricted", async () => {
  const f = prepared();
  f.records.set("safetyPlans/owner", {safeContactUids: ["friend"], ownerName: "Owner", updatedAt: 10});
  f.records.set("safetyPlans/other", {safeContactUids: ["someone_else"]});
  await assert.rejects(f.api.safetyPlansSharedWithMe({}), {code: "permission-denied"});
  f.setCaller({uid: "friend", role: "SAFE_CONTACT"});
  assert.deepEqual((await f.api.safetyPlansSharedWithMe({})).plans.map(p => p.ownerUid), ["owner"]);
  await assert.rejects(f.api.safetyPlanAvailability({data: {available: true}}), {code: "permission-denied"});
  f.setCaller({uid: "staff", role: "STAFF"});
  await f.api.safetyPlanAvailability({data: {available: false}});
  assert.equal(f.records.get("safetyStaff/staff").available, false);
  await assert.rejects(f.api.safetyPlanAvailability({data: {available: "yes"}}), {code: "permission-denied"});
});
test("consultation lists hide completed tickets and repair their stale plan assignment", async () => {
  const f = prepared();
  f.setCaller({uid: "staff", role: "STAFF"});
  for (const [owner, closed] of [["open", false], ["closed", true]]) {
    f.records.set(`safetyPlans/${owner}`, {ownerUid: owner, staffUid: "staff", consultationRequested: true, consultationConversationId: `ticket-${owner}`, ticketNumber: 10});
    f.records.set(`conversations/ticket-${owner}`, {closedAt: closed ? 100 : null});
  }
  const result = await f.api.safetyPlanConsultations({});
  assert.deepEqual(result.plans.map(p => p.ownerUid), ["open"]);
  assert.equal(f.records.get("safetyPlans/closed").consultationRequested, false);
  assert.equal(f.records.get("safetyPlans/closed").staffUid, "");
  f.setCaller({uid: "owner", role: "USER"});
  await assert.rejects(f.api.safetyPlanConsultations({}), {code: "permission-denied"});
});

test("safety caller uses trusted auth claims and refuses disabled users and invalid owner references", async () => {
  const f = fixture(), access = f.load("safetyPlanAccess");
  await assert.rejects(access.safetyCaller({}), {code: "unauthenticated"});
  f.users.set("user", {uid: "user", displayName: "Owner", customClaims: {role: "USER"}});
  assert.equal((await access.safetyCaller({auth: {uid: "user"}, data: {role: "ADMIN"}})).role, "USER");
  f.users.get("user").disabled = true;
  await assert.rejects(access.safetyCaller({auth: {uid: "user"}}), {code: "permission-denied"});
  for (const uid of ["../other", "", null]) assert.throws(() => access.planReference(uid), {code: "invalid-argument"});
  const plan = {staffUid: "staff", consultationRequested: true, safeContactUids: ["friend"]};
  assert.equal(access.canRead({uid: "friend", role: "SAFE_CONTACT"}, plan, "owner"), true);
  assert.equal(access.canRead({uid: "other", role: "SAFE_CONTACT"}, plan, "owner"), false);
  assert.equal(access.canEdit({uid: "staff", role: "STAFF"}, {...plan, consultationRequested: false}, "owner"), false);
});
