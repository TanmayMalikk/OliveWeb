const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
test("regular tickets choose eligible available staff, get distinct numbers and cancel without duplicate workload changes", async () => {
  const f = fixture(), api = f.load("supportTickets");
  for (const uid of ["available", "busy", "disabled"]) {
    f.users.set(uid, {uid, disabled: uid === "disabled", customClaims: {role: "STAFF"}});
    f.profiles.set(uid, {username: `${uid}_staff`, fullName: uid, dob: "1990-01-01", email: `${uid}@example.invalid`});
  }
  f.records.set("safetyStaff/busy", {available: false});
  const one = (await api.staffSupportRequest({data: {action: "request"}})).ticket;
  const two = (await api.staffSupportRequest({data: {action: "request"}})).ticket;
  assert.equal(one.staffUid, "available"); assert.equal(two.ticketNumber, one.ticketNumber + 1);
  assert.notEqual(one.conversationId, two.conversationId);
  assert.equal(f.records.get("safetyStaff/available").activeCount, 2);
  await api.staffSupportRequest({data: {action: "stop", conversationId: one.conversationId}});
  await api.staffSupportRequest({data: {action: "stop", conversationId: one.conversationId}});
  assert.equal(f.records.get("safetyStaff/available").activeCount, 1);
  assert.equal(f.records.get(`conversations/${one.conversationId}`).ticketStatus, "COMPLETED");
  assert.equal(f.records.get(`conversations/${two.conversationId}`).closedAt, undefined);
});
test("ticket cancellation cannot close another owner's conversation", async () => {
  const f = fixture(), api = f.load("supportTickets");
  f.records.set("supportRequests/someone-else", {ownerUid: "other", requested: true, conversationId: "support-other"});
  await assert.rejects(api.staffSupportRequest({data: {action: "stop", conversationId: "someone-else"}}), {code: "permission-denied"});
  f.setCaller({uid: "staff", role: "STAFF"});
  await assert.rejects(api.staffSupportRequest({data: {action: "request"}}), {code: "permission-denied"});
});
test("FCM assignments notify staff/admin once with no plaintext, exclude the sender and discard expired tokens", async () => {
  const f = fixture(), deliveries = [];
  f.services["./firebaseAdminClient"].messaging = {sendEachForMulticast: async message => {
    deliveries.push(message);
    return {responses: message.tokens.map(token => token === "expired" ? {success: false, error: {code: "messaging/registration-token-not-registered"}} : {success: true})};
  }};
  const api = f.load("supportTickets");
  for (const uid of ["staff", "admin", "owner"]) f.records.set(`notificationTokens/${uid}/devices/phone`, {token: uid === "staff" ? "expired" : `token-${uid}`});
  f.records.set("notificationTokens/admin/devices/blank", {token: " "});
  const after = {type: "SUPPORT", participantIds: ["owner", "staff", "admin"], staffUid: "staff", adminUid: "admin", lastMessageId: "one", lastSenderUid: "owner"};
  await api.notifyConversationUpdate({params: {conversationId: "chat"}, data: {before: {data: () => ({})}, after: {data: () => after}}});
  assert.deepEqual(deliveries.map(d => d.data.recipientId).sort(), ["admin", "staff"]);
  assert.ok(deliveries.every(d => d.data.eventId === "ticket-assigned" && d.android.priority === "high"));
  assert.ok(deliveries.every(d => !Object.hasOwn(d.data, "body")));
  assert.equal(f.records.has("notificationTokens/staff/devices/phone"), false);
  const count = deliveries.length;
  await api.notifyConversationUpdate({params: {conversationId: "chat"}, data: {before: {data: () => after}, after: {data: () => after}}});
  assert.equal(deliveries.length, count);
});
