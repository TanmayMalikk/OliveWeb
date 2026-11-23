// Automated email notifications across User, Safe Contact, Staff, and Admin events.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated, onDocumentUpdated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { auth, db, FieldValue } = require("./firebaseAdminClient");
const { roleFromUser, text } = require("./accountPolicy");
const { listEveryUser, readProfile } = require("./accountStorage");
const {
  EMAIL_SECRETS,
  buildEmail,
  sendEmailOnce,
  resolveEmailRecipient,
  claimCooldown,
} = require("./emailService");

const SHORT_LOCATION_THRESHOLD_MS = 60_000;
const SAFETY_PLAN_EMAIL_COOLDOWN_MS = 5 * 60_000;
const APP_DOWNLOAD_URL = "https://yourolivebranch.ca/get";

const runtime = {
  delay: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now: () => Date.now(),
};

function _setRuntimeOverrides(overrides = {}) {
  if (typeof overrides.delay === "function") runtime.delay = overrides.delay;
  if (typeof overrides.now === "function") runtime.now = overrides.now;
}

function snapData(snap) {
  if (!snap || snap.exists === false) return null;
  return typeof snap.data === "function" ? snap.data() || null : null;
}

async function lookupUsername(uid) {
  if (typeof uid !== "string" || !uid) return "";
  try {
    const snap = await db.collection("accountDirectory").where("uid", "==", uid).limit(1).get();
    const dirName = text(snap.docs?.[0]?.get?.("username"), 24).toLowerCase();
    if (dirName) return dirName;
  } catch {
    // Fall back to the stored profile when the directory lookup is unavailable.
  }
  const profile = await readProfile(uid).catch(() => null);
  return text(profile?.username, 24).toLowerCase();
}

function ticketMetadata(conversation = {}) {
  const isConsult =
    conversation.supportOrigin === "PLAN_CONSULT" || conversation.supportTag === "PLAN_CONSULT";
  const isGroup = conversation.requestSource === "GROUP_CHAT";
  const typeLabel = isConsult
    ? "Safety Plan Consultation"
    : isGroup
      ? "Group Support Ticket"
      : "Support Ticket";
  const hasNumber = Number.isSafeInteger(conversation.ticketNumber) && conversation.ticketNumber >= 0;
  const ticketLabel = hasNumber ? `${typeLabel} #${conversation.ticketNumber}` : typeLabel;
  return { typeLabel, ticketLabel };
}

function formatDurationLabel(durationMs) {
  const ms = Number(durationMs) || 0;
  if (ms <= 0) return "";
  if (ms < 60_000) {
    const seconds = Math.max(1, Math.round(ms / 1_000));
    return `${seconds} second${seconds === 1 ? "" : "s"}`;
  }
  if (ms < 3_600_000) {
    const minutes = Math.max(1, Math.round(ms / 60_000));
    return `${minutes} minute${minutes === 1 ? "" : "s"}`;
  }
  const hours = Math.max(1, Math.round(ms / 3_600_000));
  return `${hours} hour${hours === 1 ? "" : "s"}`;
}

function locationWarningLeadMs(durationMs) {
  const ms = Number(durationMs) || 0;
  return Math.min(15 * 60_000, Math.max(60_000, Math.floor(ms * 0.25)));
}

async function sendLocationEndingSoonEmail(ownerUid, session) {
  if (!ownerUid || !session?.sessionId) return { sent: false, reason: "invalid_session" };
  const recipient = await resolveEmailRecipient(ownerUid, ["USER"]);
  if (!recipient) return { sent: false, reason: "ineligible" };
  const mail = buildEmail({
    subject: "Olive Branch: Live location sharing is about to end",
    heading: "Live location sharing ending soon",
    paragraphs: [
      "Your active live location sharing session with your safe contacts will end soon.",
      "Open Olive Branch if you would like to extend sharing or stop it early.",
    ],
  });
  return sendEmailOnce(
    `loc-ending-user:${ownerUid}:${session.sessionId}`,
    { uid: ownerUid, eventType: "location_ending_soon" },
    { to: recipient.email, ...mail },
  );
}

// 1. User / Safe Contact: someone claimed their friend invite link or code.
async function handleFriendInviteUpdated(event) {
  const before = snapData(event.data?.before) || {};
  const after = snapData(event.data?.after) || {};
  const ownerUid = text(after.ownerUid, 128);
  const claimedBy = text(after.claimedBy, 128);
  if (!ownerUid || !claimedBy || before.claimedBy || claimedBy === ownerUid) return;
  const recipient = await resolveEmailRecipient(ownerUid, ["USER", "SAFE_CONTACT"]);
  if (!recipient) return;
  const claimantName = await lookupUsername(claimedBy);
  const who = claimantName ? `@${claimantName}` : "Someone";
  const via = recipient.role === "SAFE_CONTACT" ? "invite code" : "invite link";
  const mail = buildEmail({
    subject: "Olive Branch: Someone added you as a friend",
    heading: "New friend connection",
    paragraphs: [
      `${who} used your ${via} to add you as a friend on Olive Branch.`,
      "Open Olive Branch to view your friends list and start a secure conversation.",
    ],
  });
  await sendEmailOnce(
    `invite-claimed:${event.params.codeHash}:${claimedBy}`,
    { uid: ownerUid, eventType: "friend_invite_claimed" },
    { to: recipient.email, ...mail },
  );
}

// 2. User + Safe Contact: live location sharing started (and short-duration ending-soon check).
async function handleLiveLocationSessionWritten(event) {
  const ownerUid = text(event.params?.ownerUid, 128);
  const before = snapData(event.data?.before);
  const after = snapData(event.data?.after);
  if (!ownerUid || !after || after.active !== true) return;
  const sessionId = text(after.sessionId, 64);
  if (!sessionId || sessionId === before?.sessionId) return;

  const rawRecipients = [...new Set(
    (Array.isArray(after.recipientUids) ? after.recipientUids : [])
      .filter((uid) => typeof uid === "string" && uid && uid !== ownerUid),
  )];
  const friendsSnapshot = await db.collection("friendLists").doc(ownerUid).collection("friends").get();
  const friendUids = new Set(
    friendsSnapshot.docs.map((row) => row.get("friendUid")).filter((uid) => typeof uid === "string" && uid),
  );
  const validatedRecipients = rawRecipients.filter((uid) => friendUids.has(uid));

  const startedAt = Number(after.startedAt) || runtime.now();
  const expiresAt = Number(after.expiresAt) || 0;
  const durationMs = Math.max(0, expiresAt - startedAt);
  const durationLabel = formatDurationLabel(durationMs);

  const owner = await resolveEmailRecipient(ownerUid, ["USER"]);
  console.info(JSON.stringify({
    eventType: "live_location_session",
    rawRecipientCount: rawRecipients.length,
    validatedRecipientCount: validatedRecipients.length,
    ownerEligible: Boolean(owner),
  }));
  const tasks = [];

  if (owner) {
    const count = validatedRecipients.length || rawRecipients.length;
    const ownerMail = buildEmail({
      subject: "Olive Branch: Live location sharing started",
      heading: "Live location sharing is active",
      paragraphs: [
        `You started sharing your live location with ${count} safe contact${count === 1 ? "" : "s"}${durationLabel ? ` for ${durationLabel}` : ""}.`,
        "You remain in control and can stop sharing anytime in the Location tab or notification.",
      ],
    });
    tasks.push(sendEmailOnce(
      `loc-start-user:${ownerUid}:${sessionId}`,
      { uid: ownerUid, eventType: "location_start_user" },
      { to: owner.email, ...ownerMail },
    ));
  }

  const ownerUsername = owner?.username || await lookupUsername(ownerUid);
  const ownerLabel = ownerUsername ? `@${ownerUsername}` : "A user";
  for (const recipientUid of validatedRecipients) {
    tasks.push((async () => {
      const contact = await resolveEmailRecipient(recipientUid, ["SAFE_CONTACT"]);
      if (!contact) return;
      const contactMail = buildEmail({
        subject: "Olive Branch: Someone shared their location with you",
        heading: "Live location shared with you",
        paragraphs: [
          `${ownerLabel} started sharing their live location with you on Olive Branch${durationLabel ? ` for ${durationLabel}` : ""}.`,
          "Open Olive Branch to view the live map while sharing remains active.",
        ],
      });
      await sendEmailOnce(
        `loc-start-contact:${ownerUid}:${recipientUid}:${sessionId}`,
        { uid: recipientUid, eventType: "location_shared_with_contact" },
        { to: contact.email, ...contactMail },
      );
    })());
  }

  await Promise.all(tasks);

  // Short test shares (e.g. 10 seconds) expire faster than a 1-minute scheduler tick.
  if (durationMs > 0 && durationMs <= SHORT_LOCATION_THRESHOLD_MS) {
    await runtime.delay(Math.max(1_000, Math.floor(durationMs / 2)));
    const sessionRef = db.collection("liveLocationSessions").doc(ownerUid);
    const fresh = await sessionRef.get();
    const current = fresh.exists ? fresh.data() : null;
    if (
      current &&
      current.active === true &&
      current.sessionId === sessionId &&
      !current.endingSoonNotified
    ) {
      await sendLocationEndingSoonEmail(ownerUid, current);
      await sessionRef.set({ endingSoonNotified: true }, { merge: true });
    }
  }
}

// 3. Scheduled check: sends "about to end" warning emails for active live location sessions.
async function handleExpiringLocationShares() {
  const now = runtime.now();
  const activeSessions = await db.collection("liveLocationSessions").where("active", "==", true).get();
  await Promise.all(activeSessions.docs.map(async (docSnapshot) => {
    const data = docSnapshot.data() || {};
    const ownerUid = docSnapshot.id;
    const startedAt = Number(data.startedAt) || 0;
    const expiresAt = Number(data.expiresAt) || 0;
    if (expiresAt > 0 && expiresAt <= now) {
      await docSnapshot.ref.delete().catch(() => {});
      return;
    }
    const durationMs = Math.max(0, expiresAt - startedAt);
    const leadMs = locationWarningLeadMs(durationMs);
    if (!data.endingSoonNotified && expiresAt > now && expiresAt - now <= leadMs) {
      await sendLocationEndingSoonEmail(ownerUid, data);
      await docSnapshot.ref.set({ endingSoonNotified: true }, { merge: true });
    }
  }));
}

// 4. Support tickets: Staff assigned, Admin escalated, Safe Contact added, and Staff/Admin replies.
async function handleConversationWritten(event) {
  const before = snapData(event.data?.before) || {};
  const after = snapData(event.data?.after);
  const conversationId = text(event.params?.conversationId, 300);
  if (!after || after.type !== "SUPPORT" || after.closedAt) return;

  const participantIds = Array.isArray(after.participantIds) ? after.participantIds : [];
  const beforeParticipants = new Set(Array.isArray(before.participantIds) ? before.participantIds : []);
  const { typeLabel, ticketLabel } = ticketMetadata(after);
  const tasks = [];

  // a) Staff: new ticket assigned + ticket type
  if (after.staffUid && after.staffUid !== before.staffUid && participantIds.includes(after.staffUid)) {
    tasks.push((async () => {
      const staff = await resolveEmailRecipient(after.staffUid, ["STAFF"]);
      if (!staff) return;
      const mail = buildEmail({
        subject: `Olive Branch: New ${typeLabel} assigned to you`,
        heading: "New ticket assigned",
        paragraphs: [
          `A new ticket (${ticketLabel}) has been assigned to you on Olive Branch.`,
          `Ticket type: ${typeLabel}`,
          "Open Olive Branch Workspace to review the ticket and respond securely.",
        ],
      });
      await sendEmailOnce(
        `ticket-assigned-staff:${conversationId}:${after.staffUid}`,
        { uid: after.staffUid, eventType: "ticket_assigned_staff" },
        { to: staff.email, ...mail },
      );
    })());
  }

  // b) Admin: new escalation ticket assigned
  if (after.adminUid && after.adminUid !== before.adminUid && participantIds.includes(after.adminUid)) {
    tasks.push((async () => {
      const admin = await resolveEmailRecipient(after.adminUid, ["ADMIN"]);
      if (!admin) return;
      const mail = buildEmail({
        subject: `Olive Branch: Escalation ticket assigned (${ticketLabel})`,
        heading: "Escalated ticket assigned to you",
        paragraphs: [
          `An escalated ticket (${ticketLabel}) now requires administrator assistance and has been assigned to you.`,
          `Original ticket type: ${typeLabel}`,
          "Open Olive Branch Workspace to review and support the conversation.",
        ],
      });
      await sendEmailOnce(
        `ticket-escalated-admin:${conversationId}:${after.adminUid}`,
        { uid: after.adminUid, eventType: "ticket_escalated_admin" },
        { to: admin.email, ...mail },
      );
    })());
  }

  // c) Safe Contact: added to ticket chats for a user
  const addedUids = participantIds.filter((uid) =>
    typeof uid === "string" &&
    uid &&
    !beforeParticipants.has(uid) &&
    uid !== after.ownerUid &&
    uid !== after.staffUid &&
    uid !== after.adminUid,
  );
  if (addedUids.length > 0) {
    const ownerName = await lookupUsername(after.ownerUid);
    const ownerLabel = ownerName ? `@${ownerName}` : "A user";
    for (const addedUid of addedUids) {
      tasks.push((async () => {
        const safeContact = await resolveEmailRecipient(addedUid, ["SAFE_CONTACT"]);
        if (!safeContact) return;
        const mail = buildEmail({
          subject: "Olive Branch: You were added to a support ticket chat",
          heading: "Added to a user's ticket chat",
          paragraphs: [
            `${ownerLabel} added you as a trusted participant to their ${ticketLabel} chat on Olive Branch.`,
            "Open Olive Branch to participate in the encrypted conversation.",
          ],
        });
        await sendEmailOnce(
          `ticket-added-contact:${conversationId}:${addedUid}:${event.id || participantIds.length}`,
          { uid: addedUid, eventType: "ticket_added_safe_contact" },
          { to: safeContact.email, ...mail },
        );
      })());
    }
  }

  // d) User: replies on ticket chats from Staff or Admins
  const lastMessageId = text(after.lastMessageId, 128);
  const previousMessageId = text(before.lastMessageId, 128);
  const lastSenderUid = text(after.lastSenderUid, 128);
  if (
    lastMessageId &&
    lastMessageId !== previousMessageId &&
    lastSenderUid &&
    lastSenderUid !== after.ownerUid &&
    after.ownerUid
  ) {
    tasks.push((async () => {
      let senderUser;
      try {
        senderUser = await auth.getUser(lastSenderUid);
      } catch {
        return;
      }
      const senderRole = roleFromUser(senderUser);
      if (senderRole !== "STAFF" && senderRole !== "ADMIN") return;
      const owner = await resolveEmailRecipient(after.ownerUid, ["USER"]);
      if (!owner) return;
      const responderLabel = senderRole === "ADMIN" ? "an administrator" : "Olive Branch staff";
      const mail = buildEmail({
        subject: "Olive Branch: New reply on your ticket chat",
        heading: "New support reply",
        paragraphs: [
          `You received a new reply from ${responderLabel} on your ${ticketLabel}.`,
          "Open Olive Branch to decrypt and read the message securely.",
        ],
      });
      await sendEmailOnce(
        `ticket-reply:${conversationId}:${lastMessageId}:${after.ownerUid}`,
        { uid: after.ownerUid, eventType: "ticket_reply_user" },
        { to: owner.email, ...mail },
      );
    })());
  }

  await Promise.all(tasks);
}

// 5. User: safety plan changed by staff (rate-limited by cooldown to avoid rapid autosave spam).
async function handleSafetyPlanWritten(event) {
  const ownerUid = text(event.params?.ownerUid, 128);
  const before = snapData(event.data?.before) || {};
  const after = snapData(event.data?.after);
  if (!ownerUid || !after) return;
  const afterRev = Number(after.lastStaffEditRevision || 0);
  const beforeRev = Number(before.lastStaffEditRevision || 0);
  if (!Number.isFinite(afterRev) || afterRev <= 0 || afterRev <= beforeRev) return;

  const owner = await resolveEmailRecipient(ownerUid, ["USER"]);
  if (!owner) return;
  const allowedByCooldown = await claimCooldown(
    `safety-plan--${ownerUid}`,
    SAFETY_PLAN_EMAIL_COOLDOWN_MS,
    runtime.now(),
  );
  if (!allowedByCooldown) return;

  const mail = buildEmail({
    subject: "Olive Branch: Your safety plan was updated",
    heading: "Safety plan change indicator",
    paragraphs: [
      "An Olive Branch support staff member updated one or more sections in your safety plan.",
      "Open your safety plan in Olive Branch to review the changes or resolve any conflicting edits.",
    ],
  });
  await sendEmailOnce(
    `safety-plan-edit:${ownerUid}:${afterRev}`,
    { uid: ownerUid, eventType: "safety_plan_staff_updated" },
    { to: owner.email, ...mail },
  );
}

// 6. User: one-time email per newly published Android release version.
async function handleAppReleaseWritten(event) {
  const before = snapData(event.data?.before) || {};
  const after = snapData(event.data?.after);
  const versionCode = Number(after?.versionCode);
  const previousCode = Number(before?.versionCode || 0);
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0 || versionCode <= previousCode) return;

  const versionName = text(after.versionName, 40) || String(versionCode);
  const notes = Array.isArray(after.releaseNotes)
    ? after.releaseNotes.map((line) => text(line, 200)).filter(Boolean).slice(0, 10)
    : [];
  const allUsers = await listEveryUser();
  const targets = allUsers.filter((user) => !user.disabled && roleFromUser(user) === "USER");

  await Promise.all(targets.map(async (user) => {
    const recipient = await resolveEmailRecipient(user.uid, ["USER"]);
    if (!recipient) return;
    const paragraphs = [
      `A new Olive Branch app update (${versionName}) is now available.`,
      ...(notes.length > 0 ? [`Release notes: ${notes.join("; ")}`] : []),
      `Download the latest signed APK anytime at ${APP_DOWNLOAD_URL}.`,
    ];
    const mail = buildEmail({
      subject: `Olive Branch: App update available (${versionName})`,
      heading: "New app update available",
      paragraphs,
    });
    await sendEmailOnce(
      `app-update:${versionCode}:${user.uid}`,
      { uid: user.uid, eventType: "app_update" },
      { to: recipient.email, ...mail },
    );
  }));
}

// 7. Admin: emergency response needed when an emergency report is created.
async function handleEmergencyCreated(event) {
  const emergencyId = text(event.params?.id, 128);
  if (!emergencyId) return;
  const allUsers = await listEveryUser();
  const admins = allUsers.filter((user) => !user.disabled && roleFromUser(user) === "ADMIN");
  await Promise.all(admins.map(async (adminUser) => {
    const recipient = await resolveEmailRecipient(adminUser.uid, ["ADMIN"]);
    if (!recipient) return;
    const mail = buildEmail({
      subject: "URGENT — Olive Branch: Emergency response needed",
      heading: "Emergency response needed",
      paragraphs: [
        "A user has started an emergency response request on Olive Branch.",
        "Open Olive Branch Workspace immediately to view active emergencies and coordinate support.",
      ],
    });
    await sendEmailOnce(
      `emergency-alert:${emergencyId}:${adminUser.uid}`,
      { uid: adminUser.uid, eventType: "emergency_response_needed" },
      { to: recipient.email, ...mail },
    );
  }));
}

// 8. User: cross-device sign-in alert (App vs Web) via client registration callable.
async function handleRecordDeviceSignIn(request) {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in first.");
  }
  const uid = request.auth.uid;
  const deviceId = typeof request.data?.deviceId === "string" ? request.data.deviceId.trim() : "";
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(deviceId)) {
    throw new HttpsError("invalid-argument", "Invalid device identifier.");
  }
  const rawPlatform = typeof request.data?.platform === "string" ? request.data.platform.trim().toUpperCase() : "";
  if (rawPlatform !== "APP" && rawPlatform !== "WEB") {
    throw new HttpsError("invalid-argument", "Invalid sign-in platform.");
  }
  const registerOnly = request.data?.registerOnly === true;

  const user = await auth.getUser(uid);
  if (user.disabled) {
    throw new HttpsError("permission-denied", "Account disabled.");
  }
  const role = roleFromUser(user);
  const devicesCollection = db.collection("accountLoginDevices").doc(uid).collection("devices");

  const { knownDevice, priorDeviceCount } = await db.runTransaction(async (transaction) => {
    const existingDevices = await transaction.get(devicesCollection);
    const known = existingDevices.docs.some((doc) => doc.id === deviceId);
    const otherCount = existingDevices.docs.filter((doc) => doc.id !== deviceId).length;
    const deviceRef = devicesCollection.doc(deviceId);
    transaction.set(deviceRef, {
      uid,
      deviceId,
      platform: rawPlatform,
      lastSeenAt: FieldValue.serverTimestamp(),
      ...(known ? {} : { firstSeenAt: FieldValue.serverTimestamp() }),
    }, { merge: true });
    return { knownDevice: known, priorDeviceCount: otherCount };
  });

  let hadPriorDevices = priorDeviceCount > 0;
  if (!hadPriorDevices && !knownDevice && !registerOnly) {
    const username = await lookupUsername(uid);
    if (username) {
      const legacyDevices = await db
        .collection("messageDirectory")
        .doc(username)
        .collection("devices")
        .limit(2)
        .get()
        .catch(() => null);
      if (legacyDevices?.docs?.some((doc) => doc.id !== deviceId)) {
        hadPriorDevices = true;
      }
    }
  }

  let alerted = false;
  if (!registerOnly && role === "USER" && !knownDevice && hadPriorDevices) {
    const recipient = await resolveEmailRecipient(uid, ["USER"]);
    if (recipient) {
      const deviceTypeLabel = rawPlatform === "APP" ? "App (Android)" : "Web";
      const timestampUtc = new Date(runtime.now()).toISOString().replace("T", " ").slice(0, 19) + " UTC";
      const mail = buildEmail({
        subject: "Olive Branch: New device signed in to your account",
        heading: "New cross-device sign-in",
        paragraphs: [
          `A new ${deviceTypeLabel} device just signed in to your Olive Branch account.`,
          `Device type: ${deviceTypeLabel}`,
          `Time: ${timestampUtc}`,
          "If this was not you, sign in to Olive Branch right away and change your password in Settings.",
        ],
      });
      const delivery = await sendEmailOnce(
        `new-device-signin:${uid}:${deviceId}`,
        { uid, eventType: "cross_device_signin" },
        { to: recipient.email, ...mail },
      );
      alerted = delivery.sent === true;
    }
  }

  return { recorded: true, knownDevice, platform: rawPlatform, alerted };
}

exports.emailOnFriendInviteClaimed = onDocumentUpdated(
  { document: "friendInvites/{codeHash}", secrets: EMAIL_SECRETS },
  handleFriendInviteUpdated,
);
exports.emailOnLiveLocationSession = onDocumentWritten(
  { document: "liveLocationSessions/{ownerUid}", secrets: EMAIL_SECRETS },
  handleLiveLocationSessionWritten,
);
exports.checkExpiringLocationShares = onSchedule(
  { schedule: "every 1 minutes", secrets: EMAIL_SECRETS },
  handleExpiringLocationShares,
);
exports.emailOnConversationUpdated = onDocumentWritten(
  { document: "conversations/{conversationId}", secrets: EMAIL_SECRETS },
  handleConversationWritten,
);
exports.emailOnSafetyPlanUpdated = onDocumentWritten(
  { document: "safetyPlans/{ownerUid}", secrets: EMAIL_SECRETS },
  handleSafetyPlanWritten,
);
exports.emailOnAppRelease = onDocumentWritten(
  { document: "appReleases/android", secrets: EMAIL_SECRETS },
  handleAppReleaseWritten,
);
exports.emailOnEmergencyCreated = onDocumentCreated(
  { document: "emergencyReports/{id}", retry: true, secrets: EMAIL_SECRETS },
  handleEmergencyCreated,
);
exports.recordDeviceSignIn = onCall({ secrets: EMAIL_SECRETS }, handleRecordDeviceSignIn);

Object.defineProperty(exports, "_test", {
  value: {
    lookupUsername,
    ticketMetadata,
    formatDurationLabel,
    locationWarningLeadMs,
    sendLocationEndingSoonEmail,
    _setRuntimeOverrides,
  },
  enumerable: false,
});
