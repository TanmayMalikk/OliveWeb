const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
test("legacy consultation repair creates a numbered chat once and keeps plan and chat IDs aligned", async () => {
  const f = fixture(), api = f.load("supportTicketRepair");
  f.records.set("safetyPlans/owner", {ownerUid: "owner", consultationRequested: true, staffUid: "staff"});
  const first = await api.repairPlanTicket("owner");
  const again = await api.repairPlanTicket("owner");
  assert.deepEqual(first, again);
  assert.equal(f.records.get("safetyPlans/owner").ticketNumber, first.ticketNumber);
  assert.equal(f.records.get(`conversations/${first.conversationId}`).ticketNumber, first.ticketNumber);
  assert.equal(f.records.get("systemCounters/supportTickets").nextNumber, 1);
});
test("key preparation normalizes legacy usernames and is limited to members of support tickets", async () => {
  const f = fixture(), api = f.load("supportTicketRepair");
  f.records.set("conversations/support-owner-legacy", {type: "SUPPORT", ownerUid: "owner", participantIds: ["owner", "staff"], participantUsernames: {owner: " OWNER_NAME ", staff: "STAFF_NAME"}});
  const result = await api.conversationPrepareKeys({data: {conversationId: "support-owner-legacy"}});
  assert.deepEqual(result.participantUsernames, {owner: "owner_name", staff: "staff_name"});
  assert.equal(result.ticketNumber, 0);
  f.setCaller({uid: "stranger", role: "USER"});
  await assert.rejects(api.conversationPrepareKeys({data: {conversationId: "support-owner-legacy"}}), {code: "permission-denied"});
  await assert.rejects(api.conversationPrepareKeys({data: {conversationId: "../invalid"}}), {code: "invalid-argument"});
});
test("preparing all tickets repairs only related plans and never modifies personal conversations", async () => {
  const f = fixture(), api = f.load("supportTicketRepair");
  f.records.set("safetyPlans/owner", {ownerUid: "owner", consultationRequested: true, staffUid: "staff"});
  f.records.set("conversations/personal", {type: "DIRECT", participantIds: ["owner", "friend"], lastMessage: "Untouched"});
  await api.supportTicketsPrepare({});
  assert.equal(f.records.get("conversations/personal").lastMessage, "Untouched");
  assert.equal(f.records.get("conversations/personal").ticketNumber, undefined);
  assert.ok(f.records.get("safetyPlans/owner").consultationConversationId);
});
test("repair does not reopen completed consultation records", async () => {
  const f = fixture(), api = f.load("supportTicketRepair");
  f.records.set("safetyPlans/owner", {ownerUid: "owner", consultationRequested: true, consultationConversationId: "plan-consult-owner-old"});
  f.records.set("conversations/plan-consult-owner-old", {closedAt: 100, ticketNumber: 77});
  await api.repairPlanTicket("owner");
  assert.equal(f.records.get("conversations/plan-consult-owner-old").closedAt, 100);
  assert.equal(f.records.has("systemCounters/supportTickets"), false);
});

test("conversationPrepareKeys reassigns uninitialized tickets to key-ready staff and leaves initialized tickets unchanged", async () => {
  const f = fixture(), api = f.load("supportTicketRepair");
  f.users.set("owner", {uid: "owner", customClaims: {role: "USER"}});
  f.users.set("staff1", {uid: "staff1", customClaims: {role: "STAFF"}});
  f.users.set("staff2", {uid: "staff2", customClaims: {role: "STAFF"}});
  f.profiles.set("owner", {username: "owner_user", role: "USER", fullName: "Owner"});
  f.profiles.set("staff1", {username: "staff_unready", role: "STAFF", fullName: "Unready", email: "s1@example.invalid", dob: "1990-01-01"});
  f.profiles.set("staff2", {username: "staff_ready", role: "STAFF", fullName: "Ready", email: "s2@example.invalid", dob: "1990-01-01"});
  f.records.set("messageDirectory/staff_ready", {uid: "staff2", username: "staff_ready", publicKey: "pk-staff2"});
  f.records.set("safetyStaff/staff1", {available: true, activeCount: 1});
  f.records.set("safetyStaff/staff2", {available: true, activeCount: 0});
  f.records.set("supportRequests/support-owner-stuck", {ownerUid: "owner", staffUid: "staff1", staffName: "Unready", conversationId: "support-owner-stuck", requested: true});
  f.records.set("conversations/support-owner-stuck", {
    type: "SUPPORT", ownerUid: "owner", staffUid: "staff1",
    supportOrigin: "SUPPORT", ticketNumber: 545,
    participantIds: ["owner", "staff1"], participantUsernames: {owner: "owner_user", staff1: "staff_unready"},
    lastMessage: "", lastMessageId: "",
  });

  const repaired = await api.conversationPrepareKeys({data: {conversationId: "support-owner-stuck"}});
  assert.deepEqual(repaired.participantIds, ["owner", "staff2"]);
  assert.deepEqual(repaired.participantUsernames, {owner: "owner_user", staff2: "staff_ready"});
  assert.equal(f.records.get("conversations/support-owner-stuck").staffUid, "staff2");
  assert.equal(f.records.get("supportRequests/support-owner-stuck").staffUid, "staff2");
  assert.equal(f.records.get("safetyStaff/staff1").activeCount, 0);
  assert.equal(f.records.get("safetyStaff/staff2").activeCount, 1);

  f.records.set("conversations/support-owner-active", {
    type: "SUPPORT", ownerUid: "owner", staffUid: "staff1",
    supportOrigin: "SUPPORT", ticketNumber: 546,
    participantIds: ["owner", "staff1"], participantUsernames: {owner: "owner_user", staff1: "staff_unready"},
    roomKeys: {owner: "wrapped"}, lastMessage: "Hi", lastMessageId: "msg-1",
  });
  const untouched = await api.conversationPrepareKeys({data: {conversationId: "support-owner-active"}});
  assert.deepEqual(untouched.participantIds, ["owner", "staff1"]);
  assert.equal(f.records.get("conversations/support-owner-active").staffUid, "staff1");
});
