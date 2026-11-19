const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");

function setup() {
  const f = fixture();
  for (const [uid, role] of [["owner", "USER"], ["contact", "SAFE_CONTACT"], ["other", "USER"]]) {
    f.users.set(uid, {uid, displayName: `example_${uid}`, customClaims: {role}});
    f.records.set(`accountDirectory/example_${uid}`, {uid, username: `example_${uid}`, role});
  }
  f.setCaller({uid: "owner", role: "USER"});
  return {...f, api: f.load("friendInvites")};
}
const hash = code => require("node:crypto").createHash("sha256").update(code).digest("hex");

test("invites are generated only on request, last 48 hours, and refresh revokes the old code", async () => {
  const f = setup();
  assert.equal(await f.api.friendInviteCurrent({}), null);
  assert.equal([...f.records.keys()].some(key => key.startsWith("friendInvite")), false);
  const start = Date.now();
  const first = await f.api.friendInviteCreate({});
  assert.match(first.code, /^[A-Za-z0-9_-]{12}$/);
  assert.equal(first.link, `https://yourolivebranch.ca/get/?invite=${first.code}`);
  assert.ok(first.expiresAt >= start + 48 * 60 * 60 * 1000);
  assert.deepEqual(await f.api.friendInviteCreate({}), first);
  const second = await f.api.friendInviteCreate({data: {refresh: true}});
  assert.notEqual(second.code, first.code);
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  await assert.rejects(f.api.friendInviteClaim({data: {code: first.code}}), {code: "failed-precondition"});
});

test("claim connects both friends and a DM atomically and is one use with safe claimant retries", async () => {
  const f = setup();
  const invite = await f.api.friendInviteCreate({});
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  const result = await f.api.friendInviteClaim({data: {code: ` ${invite.code} `}});
  assert.equal(result.friendshipId, "contact--owner");
  assert.equal(f.records.get("friendLists/contact/friends/contact--owner").friendUid, "owner");
  assert.equal(f.records.get("friendLists/owner/friends/contact--owner").friendUid, "contact");
  assert.deepEqual(f.records.get("conversations/contact--owner").participantIds, ["contact", "owner"]);
  assert.equal(f.records.get("friendInviteOwners/owner").claimedBy, "contact");
  assert.deepEqual(await f.api.friendInviteClaim({data: {code: invite.code}}), result);
  f.setCaller({uid: "other", role: "USER"});
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "failed-precondition"});
});

test("expiry, self claim, organization accounts, and deleted or disabled owners cannot create a connection", async () => {
  const f = setup();
  const invite = await f.api.friendInviteCreate({});
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "invalid-argument"});
  f.setCaller({uid: "staff", role: "STAFF"});
  await assert.rejects(f.api.friendInviteCreate({}), {code: "permission-denied"});
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "permission-denied"});
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  const value = f.records.get(`friendInvites/${hash(invite.code)}`);
  value.expiresAt = Date.now();
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "failed-precondition"});
  value.expiresAt = Date.now() + 1000;
  f.users.get("owner").disabled = true;
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "failed-precondition"});
  f.users.delete("owner");
  await assert.rejects(f.api.friendInviteClaim({data: {code: invite.code}}), {code: "failed-precondition"});
  assert.equal(f.records.has("friendships/contact--owner"), false);
});

test("repeat connections preserve chat history and do not impose a total friend limit", async () => {
  const f = setup();
  let invite = await f.api.friendInviteCreate({});
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  await f.api.friendInviteClaim({data: {code: invite.code}});
  f.records.get("conversations/contact--owner").lastMessageId = "preserve-this";
  f.setCaller({uid: "owner", role: "USER"});
  invite = await f.api.friendInviteCreate({});
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  await f.api.friendInviteClaim({data: {code: invite.code}});
  assert.equal(f.records.get("conversations/contact--owner").lastMessageId, "preserve-this");
  f.setCaller({uid: "other", role: "USER"});
  const third = await f.api.friendInviteCreate({});
  f.setCaller({uid: "contact", role: "SAFE_CONTACT"});
  await f.api.friendInviteClaim({data: {code: third.code}});
  assert.equal([...f.records.keys()].filter(key => key.startsWith("friendLists/contact/friends/")).length, 2);
});

test("code guesses are rate limited without consuming an owner's invite", async () => {
  const f = setup();
  f.records.set("friendInviteAttempts/owner", {windowStart: Date.now(), count: 60});
  await assert.rejects(f.api.friendInviteClaim({data: {code: "unknowncode"}}), {code: "resource-exhausted"});
});

test("unconnected username discovery is denied while group key access is scoped to a conversation", async () => {
  const f = setup(), api = f.load("messagingPeerAccess");
  f.records.set("messageDirectory/example_contact", {uid: "contact", username: "example_contact", publicKey: "key"});
  await assert.rejects(api.messagingAuthorizePeer({data: {username: "example_contact"}}), {code: "permission-denied"});
  f.records.set("conversations/group", {participantIds: ["owner", "contact", "other"]});
  assert.deepEqual(await api.messagingAuthorizePeer({data: {username: "example_contact"}}), {uid: "contact"});
  assert.deepEqual(f.records.get("messagingPeerAccess/owner/peers/contact"), {conversationId: "group"});
  f.records.get("conversations/group").participantIds = ["other", "contact"];
  await assert.rejects(api.messagingAuthorizePeer({data: {username: "example_contact"}}), {code: "permission-denied"});
});
