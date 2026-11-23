const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");

function createEmailFixture(params = { SMTP_USER: "donotreply@yourolivebranch.ca", SMTP_PASS: "secret-pass" }) {
  const f = fixture({ _params: params });
  const sent = [];
  const emailService = f.load("emailService");
  emailService._test._setTransportFactory((config) => ({
    sendMail: async (mail) => {
      sent.push({ config, mail });
      return { messageId: `msg-${sent.length}` };
    },
  }));
  const accountPolicy = f.load("accountPolicy");
  const notifications = f.load("emailNotifications");
  notifications._test._setRuntimeOverrides({
    delay: async () => {},
    now: () => 1_700_000_000_000,
  });
  return { ...f, sent, emailService, accountPolicy, notifications };
}

function seedAccount(f, uid, role, { username = uid, email = "", consent = false, disabled = false } = {}) {
  f.users.set(uid, { uid, displayName: username, disabled, customClaims: { role } });
  f.profiles.set(uid, {
    username,
    fullName: `${uid} name`,
    dob: "1995-01-01",
    language: "English",
    address: { city: "Edmonton" },
    role,
    email,
    emailCommunicationConsent: consent,
  });
  f.records.set(`accountDirectory/${username}`, { uid, username, role });
}

test("accountPolicy validates real emails and binds consent to valid personal emails", () => {
  const { accountPolicy } = createEmailFixture();
  assert.equal(accountPolicy.validEmail("user@example.com"), true);
  assert.equal(accountPolicy.validEmail("user@users.olive.invalid"), false);
  assert.equal(accountPolicy.validEmail("bad-email"), false);

  const consented = accountPolicy.defaultProfile("olive_user", "USER", {
    email: " User@Example.com ",
    emailCommunicationConsent: true,
    address: { city: "Edmonton" },
  }, "olive_user@users.olive.invalid");
  assert.equal(consented.email, "user@example.com");
  assert.equal(consented.emailCommunicationConsent, true);

  const withoutConsent = accountPolicy.defaultProfile("olive_safe", "SAFE_CONTACT", {
    email: "safe@example.com",
    emailCommunicationConsent: false,
    address: { city: "Edmonton" },
  }, "olive_safe@safe.olive.invalid");
  assert.equal(withoutConsent.email, "safe@example.com");
  assert.equal(withoutConsent.emailCommunicationConsent, false);
});

test("skips sending gracefully when Hostinger SMTP credentials are missing", async () => {
  const f = createEmailFixture({ SMTP_USER: "", SMTP_PASS: "" });
  seedAccount(f, "user1", "USER", { email: "u1@example.com", consent: true });
  seedAccount(f, "friend1", "SAFE_CONTACT", { email: "f1@example.com", consent: true });
  await f.notifications.emailOnFriendInviteClaimed({
    params: { codeHash: "hash1" },
    data: {
      before: { data: () => ({ ownerUid: "user1", claimedBy: "" }) },
      after: { data: () => ({ ownerUid: "user1", claimedBy: "friend1" }) },
    },
  });
  assert.equal(f.sent.length, 0);
  // Missing SMTP configuration must not permanently burn deduplication keys.
  assert.equal([...f.records.keys()].some((k) => k.startsWith("emailNotificationLogs/")), false);
});

test("friend invite claim notifies User and Safe Contact owners only when consented", async () => {
  const f = createEmailFixture();
  seedAccount(f, "user_yes", "USER", { username: "user_yes", email: "user@example.com", consent: true });
  seedAccount(f, "user_no", "USER", { username: "user_no", email: "no@example.com", consent: false });
  seedAccount(f, "safe_yes", "SAFE_CONTACT", { username: "safe_yes", email: "safe@example.com", consent: true });
  seedAccount(f, "claimant", "USER", { username: "claimant_one" });

  await f.notifications.emailOnFriendInviteClaimed({
    params: { codeHash: "hash-user" },
    data: {
      before: { data: () => ({ ownerUid: "user_yes", claimedBy: "" }) },
      after: { data: () => ({ ownerUid: "user_yes", claimedBy: "claimant" }) },
    },
  });
  // Retry should be deduplicated cleanly.
  await f.notifications.emailOnFriendInviteClaimed({
    params: { codeHash: "hash-user" },
    data: {
      before: { data: () => ({ ownerUid: "user_yes", claimedBy: "" }) },
      after: { data: () => ({ ownerUid: "user_yes", claimedBy: "claimant" }) },
    },
  });
  await f.notifications.emailOnFriendInviteClaimed({
    params: { codeHash: "hash-no" },
    data: {
      before: { data: () => ({ ownerUid: "user_no", claimedBy: "" }) },
      after: { data: () => ({ ownerUid: "user_no", claimedBy: "claimant" }) },
    },
  });
  await f.notifications.emailOnFriendInviteClaimed({
    params: { codeHash: "hash-safe" },
    data: {
      before: { data: () => ({ ownerUid: "safe_yes", claimedBy: "" }) },
      after: { data: () => ({ ownerUid: "safe_yes", claimedBy: "claimant" }) },
    },
  });

  assert.equal(f.sent.length, 2);
  assert.equal(f.sent[0].mail.to, "user@example.com");
  assert.match(f.sent[0].mail.text, /@claimant_one.*invite link/);
  assert.equal(f.sent[1].mail.to, "safe@example.com");
  assert.match(f.sent[1].mail.text, /@claimant_one.*invite code/);
});

test("live location notifies user & safe contacts at start and notifies user before short test share ends", async () => {
  const f = createEmailFixture();
  seedAccount(f, "owner", "USER", { username: "olive_owner", email: "owner@example.com", consent: true });
  seedAccount(f, "safe1", "SAFE_CONTACT", { username: "trusted_safe", email: "safe1@example.com", consent: true });
  seedAccount(f, "nonfriend", "SAFE_CONTACT", { username: "stranger", email: "stranger@example.com", consent: true });
  f.records.set("friendLists/owner/friends/f1", { friendUid: "safe1", friendUsername: "trusted_safe" });

  const session = {
    ownerUid: "owner",
    sessionId: "sess-12345678",
    recipientUids: ["safe1", "nonfriend"],
    active: true,
    startedAt: 1_700_000_000_000,
    expiresAt: 1_700_000_010_000,
  };
  f.records.set("liveLocationSessions/owner", session);

  await f.notifications.emailOnLiveLocationSession({
    params: { ownerUid: "owner" },
    data: { before: { exists: false }, after: { exists: true, data: () => session } },
  });

  assert.equal(f.sent.length, 3);
  assert.deepEqual(
    f.sent.map((entry) => [entry.mail.to, entry.mail.subject]),
    [
      ["owner@example.com", "Olive Branch: Live location sharing started"],
      ["safe1@example.com", "Olive Branch: Someone shared their location with you"],
      ["owner@example.com", "Olive Branch: Live location sharing is about to end"],
    ],
  );
  assert.equal(f.records.get("liveLocationSessions/owner").endingSoonNotified, true);
});

test("scheduled location check warns before long session expires and stops silently if cancelled early", async () => {
  const f = createEmailFixture();
  let currentNow = 1_700_000_000_000;
  f.notifications._test._setRuntimeOverrides({ now: () => currentNow });
  seedAccount(f, "owner", "USER", { username: "olive_owner", email: "owner@example.com", consent: true });

  const expiresAt = currentNow + 3_600_000;
  f.records.set("liveLocationSessions/owner", {
    ownerUid: "owner",
    sessionId: "long-session-1",
    recipientUids: ["safe1"],
    active: true,
    startedAt: currentNow,
    expiresAt,
  });

  // 30 min remaining (> 15 min lead) -> no warning yet.
  currentNow = expiresAt - 30 * 60_000;
  await f.notifications.checkExpiringLocationShares();
  assert.equal(f.sent.length, 0);

  // 10 min remaining (<= 15 min lead) -> one warning email.
  currentNow = expiresAt - 10 * 60_000;
  await f.notifications.checkExpiringLocationShares();
  await f.notifications.checkExpiringLocationShares();
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].mail.subject, "Olive Branch: Live location sharing is about to end");

  // Past expiry -> removes session doc without sending extra email.
  currentNow = expiresAt + 1_000;
  await f.notifications.checkExpiringLocationShares();
  assert.equal(f.records.has("liveLocationSessions/owner"), false);
});

test("ticket events email Staff with type, Admin on escalation, Safe Contact when added, and User on staff/admin reply", async () => {
  const f = createEmailFixture();
  seedAccount(f, "owner", "USER", { username: "user_one", email: "owner@example.com", consent: true });
  seedAccount(f, "safe1", "SAFE_CONTACT", { username: "safe_one", email: "safe@example.com", consent: true });
  seedAccount(f, "staff1", "STAFF", { username: "staff_one", email: "staff@yourolivebranch.ca" });
  seedAccount(f, "admin1", "ADMIN", { username: "admin_one", email: "admin@yourolivebranch.ca" });

  // 1. Staff assigned to a Safety Plan Consultation ticket
  const createdTicket = {
    type: "SUPPORT",
    ownerUid: "owner",
    staffUid: "staff1",
    adminUid: "",
    supportOrigin: "PLAN_CONSULT",
    supportTag: "PLAN_CONSULT",
    ticketNumber: 42,
    participantIds: ["owner", "staff1"],
    lastMessageId: "",
    lastSenderUid: "",
  };
  await f.notifications.emailOnConversationUpdated({
    id: "evt-1",
    params: { conversationId: "plan-consult-owner-42" },
    data: { before: { exists: false }, after: { exists: true, data: () => createdTicket } },
  });
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].mail.to, "staff@yourolivebranch.ca");
  assert.match(f.sent[0].mail.text, /Ticket type: Safety Plan Consultation/);
  assert.match(f.sent[0].mail.text, /#42/);

  // 2. Safe Contact added to ticket chat + Admin escalated on same ticket
  const updatedTicket = {
    ...createdTicket,
    adminUid: "admin1",
    supportTag: "NEEDS_ADMIN",
    participantIds: ["owner", "staff1", "safe1", "admin1"],
  };
  await f.notifications.emailOnConversationUpdated({
    id: "evt-2",
    params: { conversationId: "plan-consult-owner-42" },
    data: {
      before: { exists: true, data: () => createdTicket },
      after: { exists: true, data: () => updatedTicket },
    },
  });
  assert.equal(f.sent.length, 3);
  assert.equal(f.sent[1].mail.to, "admin@yourolivebranch.ca");
  assert.match(f.sent[1].mail.subject, /Escalation ticket assigned/);
  assert.equal(f.sent[2].mail.to, "safe@example.com");
  assert.match(f.sent[2].mail.text, /@user_one added you/);

  // 3. Staff reply triggers User email (and User's own message does NOT)
  const userMessage = { ...updatedTicket, lastMessageId: "m-user", lastSenderUid: "owner" };
  await f.notifications.emailOnConversationUpdated({
    id: "evt-3",
    params: { conversationId: "plan-consult-owner-42" },
    data: { before: { exists: true, data: () => updatedTicket }, after: { exists: true, data: () => userMessage } },
  });
  assert.equal(f.sent.length, 3);

  const staffReply = { ...updatedTicket, lastMessageId: "m-staff", lastSenderUid: "staff1" };
  await f.notifications.emailOnConversationUpdated({
    id: "evt-4",
    params: { conversationId: "plan-consult-owner-42" },
    data: { before: { exists: true, data: () => userMessage }, after: { exists: true, data: () => staffReply } },
  });
  assert.equal(f.sent.length, 4);
  assert.equal(f.sent[3].mail.to, "owner@example.com");
  assert.match(f.sent[3].mail.text, /Olive Branch staff/);

  // 4. Admin reply triggers User email
  const adminReply = { ...staffReply, lastMessageId: "m-admin", lastSenderUid: "admin1" };
  await f.notifications.emailOnConversationUpdated({
    id: "evt-5",
    params: { conversationId: "plan-consult-owner-42" },
    data: { before: { exists: true, data: () => staffReply }, after: { exists: true, data: () => adminReply } },
  });
  assert.equal(f.sent.length, 5);
  assert.equal(f.sent[4].mail.to, "owner@example.com");
  assert.match(f.sent[4].mail.text, /administrator/);
});

test("app release sends one-time email per version to consented Users only", async () => {
  const f = createEmailFixture();
  seedAccount(f, "u1", "USER", { email: "u1@example.com", consent: true });
  seedAccount(f, "u2", "USER", { email: "u2@example.com", consent: false });
  seedAccount(f, "sc1", "SAFE_CONTACT", { email: "sc@example.com", consent: true });

  const release1 = { versionCode: 100000001, versionName: "v1.0.0.1", releaseNotes: ["Bug fixes"] };
  await f.notifications.emailOnAppRelease({
    params: {},
    data: { before: { exists: false }, after: { exists: true, data: () => release1 } },
  });
  // Metadata rewrite for same versionCode must not send again.
  await f.notifications.emailOnAppRelease({
    params: {},
    data: { before: { exists: true, data: () => release1 }, after: { exists: true, data: () => ({ ...release1, releaseNotes: ["Updated"] }) } },
  });
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].mail.to, "u1@example.com");
  assert.match(f.sent[0].mail.subject, /v1\.0\.0\.1/);

  // Next version sends once again.
  const release2 = { versionCode: 100000002, versionName: "v1.0.0.2", releaseNotes: ["New icons"] };
  await f.notifications.emailOnAppRelease({
    params: {},
    data: { before: { exists: true, data: () => release1 }, after: { exists: true, data: () => release2 } },
  });
  assert.equal(f.sent.length, 2);
});

test("staff safety plan edits notify User with cooldown rate limiting and zero plaintext leakage", async () => {
  const f = createEmailFixture();
  let now = 1_700_000_000_000;
  f.notifications._test._setRuntimeOverrides({ now: () => now });
  seedAccount(f, "owner", "USER", { email: "owner@example.com", consent: true });

  await f.notifications.emailOnSafetyPlanUpdated({
    params: { ownerUid: "owner" },
    data: {
      before: { exists: true, data: () => ({ lastStaffEditRevision: 0 }) },
      after: { exists: true, data: () => ({ lastStaffEditRevision: 1, fields: { primary_grab: { value: "secret passport" } } }) },
    },
  });
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].mail.text.includes("secret passport"), false);

  // Within 5-minute cooldown -> suppressed.
  now += 60_000;
  await f.notifications.emailOnSafetyPlanUpdated({
    params: { ownerUid: "owner" },
    data: {
      before: { exists: true, data: () => ({ lastStaffEditRevision: 1 }) },
      after: { exists: true, data: () => ({ lastStaffEditRevision: 2 }) },
    },
  });
  assert.equal(f.sent.length, 1);

  // After 5 minutes -> sent.
  now += 5 * 60_000;
  await f.notifications.emailOnSafetyPlanUpdated({
    params: { ownerUid: "owner" },
    data: {
      before: { exists: true, data: () => ({ lastStaffEditRevision: 2 }) },
      after: { exists: true, data: () => ({ lastStaffEditRevision: 3 }) },
    },
  });
  assert.equal(f.sent.length, 2);
});

test("cross-device sign-in alerts User showing App vs Web and password warning", async () => {
  const f = createEmailFixture();
  seedAccount(f, "u1", "USER", { username: "olive_user_1", email: "u1@example.com", consent: true });
  f.setCaller({ uid: "u1", role: "USER" });

  // 1. Account registration on Android app -> quiet baseline
  const first = await f.notifications.recordDeviceSignIn({
    auth: { uid: "u1" },
    data: { deviceId: "android_device_00001", platform: "APP", registerOnly: true },
  });
  assert.deepEqual(first, { recorded: true, knownDevice: false, platform: "APP", alerted: false });
  assert.equal(f.sent.length, 0);

  // 2. Signing in on Web triggers alert saying Web + change password advice
  const second = await f.notifications.recordDeviceSignIn({
    auth: { uid: "u1" },
    data: { deviceId: "web_browser_00000001", platform: "WEB", registerOnly: false },
  });
  assert.equal(second.alerted, true);
  assert.equal(f.sent.length, 1);
  assert.match(f.sent[0].mail.text, /Device type: Web/);
  assert.match(f.sent[0].mail.text, /change your password/i);

  // 3. Re-signing into same Web browser does NOT re-alert
  const repeat = await f.notifications.recordDeviceSignIn({
    auth: { uid: "u1" },
    data: { deviceId: "web_browser_00000001", platform: "WEB", registerOnly: false },
  });
  assert.equal(repeat.alerted, false);
  assert.equal(f.sent.length, 1);

  // 4. Signing into a second Android phone alerts showing App (Android)
  const secondPhone = await f.notifications.recordDeviceSignIn({
    auth: { uid: "u1" },
    data: { deviceId: "android_device_00002", platform: "APP", registerOnly: false },
  });
  assert.equal(secondPhone.alerted, true);
  assert.equal(f.sent.length, 2);
  assert.match(f.sent[1].mail.text, /App \(Android\)/);
});

test("emergency creation emails every active Admin with no user payload", async () => {
  const f = createEmailFixture();
  seedAccount(f, "owner", "USER", { email: "owner@example.com", consent: true });
  seedAccount(f, "admin1", "ADMIN", { email: "admin1@yourolivebranch.ca" });
  seedAccount(f, "admin_disabled", "ADMIN", { email: "disabled@yourolivebranch.ca", disabled: true });
  seedAccount(f, "staff1", "STAFF", { email: "staff@yourolivebranch.ca" });

  await f.notifications.emailOnEmergencyCreated({ params: { id: "em-123" } });
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].mail.to, "admin1@yourolivebranch.ca");
  assert.match(f.sent[0].mail.subject, /Emergency response needed/);
});
