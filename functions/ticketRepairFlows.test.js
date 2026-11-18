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
