const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
function prepared(type = "SUPPORT") {
  const f = fixture();
  for (const [uid, role] of [["owner", "USER"], ["staff", "STAFF"], ["admin", "ADMIN"], ["friend", "SAFE_CONTACT"]]) {
    f.users.set(uid, {uid, customClaims: {role}}); f.profiles.set(uid, {username: `${uid}_name`, role, fullName: uid});
  }
  f.records.set("conversations/chat", {type, ownerUid: "owner", staffUid: "staff", adminUid: "admin", participantIds: ["owner", "staff", "admin"], participantUsernames: {owner: "owner_name", staff: "staff_name", admin: "admin_name"}, roomKeys: {owner: "owner-key", staff: "staff-key", admin: "admin-key"}});
  f.records.set("friendLists/owner/friends/friendship", {friendUid: "friend", friendUsername: "friend_name"});
  return {...f, api: f.load("supportGroups")};
}
test("ticket add and removal preserve history, remove only the friend's key, and author timeline events", async () => {
  const f = prepared();
  await f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  let conversation = f.records.get("conversations/chat");
  assert.equal(conversation.type, "SUPPORT"); assert.ok(conversation.participantIds.includes("friend"));
  assert.equal(conversation.roomKeys.owner, "owner-key");
  const added = [...f.records.values()].find(v => v.action === "ADDED");
  assert.equal(added.actorUid, "owner"); assert.equal(added.participantName, "friend_name");
  f.records.set("conversations/chat", {...conversation, roomKeys: {...conversation.roomKeys, friend: "friend-key"}, participantPublicKeys: {owner: "owner-public", friend: "friend-public"}});
  f.records.set("conversations/chat/deviceKeys/friend-phone", {uid: "friend"});
  f.records.set("conversations/chat/deviceKeys/owner-phone", {uid: "owner"});
  await f.api.conversationRemoveParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  conversation = f.records.get("conversations/chat");
  assert.ok(!conversation.participantIds.includes("friend")); assert.equal(conversation.roomKeys.friend, undefined);
  assert.equal(conversation.roomKeys.owner, "owner-key"); assert.equal(conversation.participantPublicKeys.owner, "owner-public");
  assert.equal(f.records.has("conversations/chat/deviceKeys/friend-phone"), false);
  assert.equal(f.records.has("conversations/chat/deviceKeys/owner-phone"), true);
  assert.ok([...f.records.values()].some(v => v.action === "REMOVED"));
  await f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  assert.ok(f.records.get("conversations/chat").participantIds.includes("friend"));
});
test("tickets reject removing organization members and friends added by someone else", async () => {
  const f = prepared();
  for (const participantUid of ["staff", "admin"]) await assert.rejects(f.api.conversationRemoveParticipant({data: {conversationId: "chat", participantUid}}), {code: "permission-denied"});
  f.records.get("conversations/chat").participantIds.push("friend");
  await assert.rejects(f.api.conversationRemoveParticipant({data: {conversationId: "chat", participantUid: "friend"}}), {code: "permission-denied"});
  assert.ok(f.records.get("conversations/chat").participantIds.includes("friend"));
});
test("direct chats become owned groups, retries are idempotent, and closed or untrusted additions fail", async () => {
  const f = prepared("DIRECT");
  await f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  await f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  const chat = f.records.get("conversations/chat");
  assert.equal(chat.type, "GROUP"); assert.equal(chat.ownerUid, "owner"); assert.equal(chat.participantIds.filter(uid => uid === "friend").length, 1);
  await f.api.conversationRemoveParticipant({data: {conversationId: "chat", participantUid: "friend"}});
  f.records.get("conversations/chat").closedAt = 123;
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}}), {code: "failed-precondition"});
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "missing", participantUid: "friend"}}), {code: "not-found"});
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "owner"}}), {code: "invalid-argument"});
});
test("nonmembers, strangers and staff cannot add personal contacts to ticket chats", async () => {
  const f = prepared();
  f.setCaller({uid: "outsider", role: "USER"});
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}}), {code: "permission-denied"});
  f.setCaller({uid: "owner", role: "USER"}); f.records.delete("friendLists/owner/friends/friendship");
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}}), {code: "permission-denied"});
  f.records.set("friendLists/owner/friends/friendship", {friendUid: "friend"});
  f.users.get("friend").customClaims.role = "STAFF";
  await assert.rejects(f.api.conversationAddParticipant({data: {conversationId: "chat", participantUid: "friend"}}), {code: "permission-denied"});
});
test("support conversation saves preserve ticket numbers and rotate keys only when a recipient leaves", async () => {
  const f = prepared();
  await f.api.saveSupportConversation({ownerUid: "owner", staffUid: "staff", adminUid: "admin", conversationId: "chat", ticketNumber: 524});
  assert.equal(f.records.get("conversations/chat").roomKeys.owner, "owner-key");
  assert.equal(f.records.get("conversations/chat").ticketNumber, 524);
  await f.api.saveSupportConversation({ownerUid: "owner", staffUid: "staff", conversationId: "chat", supportOrigin: "PLAN_CONSULT"});
  assert.equal(f.records.get("conversations/chat").roomKeys, undefined);
  assert.equal(f.records.get("conversations/chat").supportTag, "PLAN_CONSULT");
  await f.api.closeSupportConversation("chat");
  assert.equal(f.records.get("conversations/chat").ticketStatus, "COMPLETED");
  await f.api.closeSupportConversation("chat");
  await assert.rejects(f.api.saveSupportConversation({ownerUid: "owner", conversationId: "chat"}), {code: "failed-precondition"});
});
test("only assigned staff may request admin help and the change creates a shared timeline event", async () => {
  const f = prepared();
  f.records.get("conversations/chat").adminUid = "";
  await assert.rejects(f.api.safetySupportAdmin({data: {conversationId: "chat"}}), {code: "permission-denied"});
  f.setCaller({uid: "staff", role: "STAFF"});
  await f.api.safetySupportAdmin({data: {conversationId: "chat"}});
  assert.equal(f.records.get("conversations/chat").adminUid, "admin");
  assert.equal(f.records.get("conversations/chat").supportTag, "NEEDS_ADMIN");
  assert.ok([...f.records.values()].some(v => v.action === "ADMIN_HELP_REQUESTED"));
});
test("ticket number reservations advance atomically and repair invalid counters", async () => {
  const f = fixture(), {reserveSupportTicketNumber} = f.load("supportTicketNumbers");
  assert.equal(await f.db.runTransaction(reserveSupportTicketNumber), 0);
  assert.equal(await f.db.runTransaction(reserveSupportTicketNumber), 1);
  f.records.set("systemCounters/supportTickets", {nextNumber: -1});
  assert.equal(await f.db.runTransaction(reserveSupportTicketNumber), 0);
});

test("requesting staff makes a separate numbered ticket and closing it clears workload only once", async () => {
  const f = prepared("DIRECT");
  f.records.set("conversations/chat", {type: "DIRECT", participantIds: ["owner", "friend"], participantUsernames: {owner: "owner_name", friend: "friend_name"}});
  Object.assign(f.profiles.get("staff"), {email: "staff@example.invalid", dob: "1990-01-01"});
  const result = await f.api.conversationRequestStaff({data: {conversationId: "chat"}});
  assert.ok(result.conversationId.startsWith("support-owner-"));
  assert.equal(f.records.get("conversations/chat").type, "DIRECT");
  const ticket = f.records.get(`conversations/${result.conversationId}`);
  assert.equal(ticket.ticketNumber, 0); assert.ok(ticket.participantIds.includes("staff"));
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 1);
  f.setCaller({uid: "staff", role: "STAFF"});
  await f.api.staffSupportClose({data: {conversationId: result.conversationId}});
  await f.api.staffSupportClose({data: {conversationId: result.conversationId}});
  assert.equal(f.records.get(`conversations/${result.conversationId}`).ticketStatus, "COMPLETED");
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 0);
  assert.equal(f.records.get("conversations/chat").supportConversationId, undefined);
});
test("consultations reuse active ticket numbers and reserve new numbers after stopping", async () => {
  const f = prepared(), api = f.load("safetyConsultations");
  f.records.set("safetyPlans/owner", {ownerUid: "owner", fields: {}, consultationRequested: false});
  Object.assign(f.profiles.get("staff"), {email: "staff@example.invalid", dob: "1990-01-01"});
  const first = (await api.safetyPlanConsult({data: {action: "request"}})).plan;
  const retry = (await api.safetyPlanConsult({data: {action: "request"}})).plan;
  assert.equal(first.ticketNumber, retry.ticketNumber);
  assert.equal(first.consultationConversationId, retry.consultationConversationId);
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 1);
  await api.safetyPlanConsult({data: {action: "stop"}});
  assert.equal(f.records.get(`conversations/${first.consultationConversationId}`).ticketStatus, "COMPLETED");
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 0);
  const next = (await api.safetyPlanConsult({data: {action: "request"}})).plan;
  assert.equal(next.ticketNumber, first.ticketNumber + 1);
  assert.notEqual(next.consultationConversationId, first.consultationConversationId);
});
test("consultation request assigns the available staff member with the lowest active consult tickets", async () => {
  const f = prepared(), api = f.load("safetyConsultations");
  f.users.set("staff2", {uid: "staff2", customClaims: {role: "STAFF"}});
  for (const id of ["staff", "staff2"]) {
    f.profiles.set(id, Object.assign(f.profiles.get(id) || {}, {username: `${id}_name`, role: "STAFF", fullName: id, email: `${id}@example.invalid`, dob: "1990-01-01"}));
  }
  f.records.set("safetyStaff/staff", {available: true, activeCount: 1});
  f.records.set("safetyStaff/staff2", {available: true, activeCount: 4});
  f.records.set("safetyPlans/existing-owner", {ownerUid: "existing-owner", staffUid: "staff", consultationRequested: true, consultationConversationId: "plan-consult-existing"});
  f.records.set("conversations/plan-consult-existing", {type: "SUPPORT", supportOrigin: "PLAN_CONSULT", staffUid: "staff"});
  f.records.set("safetyPlans/owner", {ownerUid: "owner", fields: {}, consultationRequested: false});
  const assigned = (await api.safetyPlanConsult({data: {action: "request"}})).plan;
  assert.equal(assigned.staffUid, "staff2");
});
test("support closing rejects personal and unrelated organization accounts", async () => {
  const f = prepared();
  const id = "support-owner-fixture";
  f.records.set(`conversations/${id}`, f.records.get("conversations/chat"));
  await assert.rejects(f.api.staffSupportClose({data: {conversationId: id}}), {code: "permission-denied"});
  f.setCaller({uid: "outsider", role: "ADMIN"});
  await assert.rejects(f.api.staffSupportClose({data: {conversationId: id}}), {code: "permission-denied"});
  await assert.rejects(f.api.staffSupportClose({data: {conversationId: "invalid"}}), {code: "invalid-argument"});
});

test("replaying a support request reuses its ticket and never reopens a canceled chat", async () => {
  const f = prepared(), api = f.load("supportTickets");
  Object.assign(f.profiles.get("staff"), {email: "staff@example.invalid", dob: "1990-01-01"});
  const requestKey = "240e6184-6365-443c-beb9-1912a94ef8ab";
  const request = {data: {action: "request", requestKey}};
  const first = (await api.staffSupportRequest(request)).ticket;
  const replay = (await api.staffSupportRequest(request)).ticket;
  assert.equal(replay.conversationId, first.conversationId);
  assert.equal(replay.ticketNumber, first.ticketNumber);
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 1);
  await api.staffSupportRequest({data: {action: "stop", conversationId: first.conversationId}});
  const closed = (await api.staffSupportRequest(request)).ticket;
  assert.equal(closed.requested, false);
  assert.equal(f.records.get("safetyStaff/staff").activeCount, 0);
  const next = (await api.staffSupportRequest({data: {action: "request"}})).ticket;
  assert.notEqual(next.conversationId, first.conversationId);
  assert.equal(next.ticketNumber, first.ticketNumber + 1);
});
