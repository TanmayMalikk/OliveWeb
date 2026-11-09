// Users, safe contacts, and staff: creates their private support conversation.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, FieldValue} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {safetyCaller, planReference} = require("./safetyPlanAccess");
const {roleFromUser} = require("./accountPolicy");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {reserveSupportTicketNumber} = require("./supportTicketNumbers");
const crypto = require("node:crypto");

const PLAN_CONSULT = "PLAN_CONSULT";
const SUPPORT = "SUPPORT";
const NEEDS_ADMIN = "NEEDS_ADMIN";

// Creates a unique conversation ID so every new support ticket gets a separate chat.
function supportConversationId(ownerUid, origin = SUPPORT) {
  const ticketId = crypto.randomUUID();
  return `${origin === PLAN_CONSULT ? "plan-consult" : "support"}-${ownerUid}-${ticketId}`;
}

// Loads display usernames for every participant stored in a support conversation.
async function participantNames(participantIds) {
  const profiles = await Promise.all(participantIds.map(readProfile));
  return Object.fromEntries(participantIds.map((uid, index) => [uid, profiles[index]?.username || uid]));
}

// Finds active staff accounts that are ready to receive another support chat.
async function eligibleStaffCandidates() {
  const result = [];
  for (const user of await listEveryUser()) {
    if (user.disabled || user.customClaims?.role !== "STAFF") continue;
    const profile = await readProfile(user.uid);
    if (staffEligible(user, profile)) result.push({uid: user.uid, name: profile.fullName});
  }
  return result;
}

// Checks group ownership, membership, and the two-person minimum before a removal.
function validateGroupRemoval(conversation, callerUid, participantUid) {
  const participantIds = Array.isArray(conversation?.participantIds) ? conversation.participantIds : [];
  if (conversation?.type !== "GROUP" || conversation.closedAt) {
    throw new HttpsError("failed-precondition", "Only an open group chat can remove a member.");
  }
  if (conversation.ownerUid !== callerUid) {
    throw new HttpsError("permission-denied", "Only the group owner can remove a member.");
  }
  if (!participantIds.includes(participantUid)) {
    throw new HttpsError("not-found", "This person is not in the group.");
  }
  if (participantIds.length <= 2) {
    throw new HttpsError("failed-precondition", "A group chat must keep at least two people.");
  }
  return participantIds.filter(uid => uid !== participantUid);
}

// Creates or updates the encrypted support chat for a support request or consultation.
async function saveSupportConversation(plan, safeContactUid = "", reopen = false) {
  if (!plan?.ownerUid || !plan?.staffUid) return;
  const origin = plan.supportOrigin === PLAN_CONSULT ? PLAN_CONSULT : SUPPORT;
  const conversationId = plan.conversationId || supportConversationId(plan.ownerUid, origin);
  const participantIds = [...new Set([
    plan.ownerUid,
    ...(Array.isArray(plan.personalParticipantUids) ? plan.personalParticipantUids : []),
    plan.staffUid,
    safeContactUid,
    plan.adminUid,
  ].filter(Boolean))];
  if (participantIds.length < 2) {
    throw new HttpsError("failed-precondition", "A support chat needs at least two people.");
  }
  const participantUsernames = await participantNames(participantIds);
  const reference = db.collection("conversations").doc(conversationId);
  const current = await reference.get();
  // Completed tickets stay immutable. A later request must receive a new ID and ticket number.
  if (current.exists && current.get("closedAt")) {
    throw new HttpsError("failed-precondition", "Create a new ticket instead of reopening a completed chat.");
  }
  const previousParticipants = current.data()?.participantIds || [];
  const participantRemoved = previousParticipants.some(uid => !participantIds.includes(uid));
  const value = {
    type: "SUPPORT", ownerUid: plan.ownerUid, staffUid: plan.staffUid, adminUid: plan.adminUid || "",
    supportOrigin: origin, supportTag: plan.adminUid ? NEEDS_ADMIN : origin,
    friendshipId: conversationId, participantIds, participantUsernames,
    updatedAt: FieldValue.serverTimestamp(), lastMessage: current.data()?.lastMessage || "",
    lastMessageId: current.data()?.lastMessageId || "", lastSenderUid: current.data()?.lastSenderUid || "",
  };
  if (Number.isSafeInteger(plan.ticketNumber) && plan.ticketNumber >= 0) {
    value.ticketNumber = plan.ticketNumber;
  }
  if (plan.requestSource) value.requestSource = plan.requestSource;
  if (plan.sourceConversationId) value.sourceConversationId = plan.sourceConversationId;
  if (!current.exists) value.createdAt = FieldValue.serverTimestamp();
  // Existing active requests may safely retry. Closed records were rejected above.
  // Adding someone preserves the old room key so prior messages stay readable.
  // Removing someone clears it so they cannot read messages sent afterward.
  if (participantRemoved) {
    value.roomKeys = FieldValue.delete();
    value.participantPublicKeys = FieldValue.delete();
  }
  await reference.set(value, {merge: true});
}

// Archives one support conversation for every participant without deleting its messages.
async function closeSupportConversation(conversationId) {
  if (!conversationId) return;
  const reference = db.collection("conversations").doc(conversationId);
  const snapshot = await reference.get();
  if (snapshot.exists && !snapshot.get("closedAt")) {
    await reference.update({
      closedAt: FieldValue.serverTimestamp(),
      completedAt: FieldValue.serverTimestamp(),
      ticketStatus: "COMPLETED",
      updatedAt: FieldValue.serverTimestamp(),
    });
  }
}

/** Confirms that organization support belongs to the ticket before allowing a global close. */
function canCloseSupportConversation(conversation, caller) {
  return conversation?.type === "SUPPORT" &&
    ["STAFF", "ADMIN"].includes(caller?.role) &&
    Array.isArray(conversation.participantIds) &&
    conversation.participantIds.includes(caller.uid);
}

// Lets a user add one approved safe contact to an active plan consultation.
exports.safetySupportContact = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "USER") throw new HttpsError("permission-denied", "Owner only.");
  const safeContactUid = typeof request.data?.safeContactUid === "string" ? request.data.safeContactUid : "";
  const reference = planReference(caller.uid);
  const snapshot = await reference.get();
  if (!snapshot.exists) throw new HttpsError("failed-precondition", "Open the safety plan first.");
  const plan = snapshot.data();
  if (!plan.staffUid || plan.consultationRequested !== true) {
    throw new HttpsError("failed-precondition", "Request a plan consultation before creating the support chat.");
  }
  // Confirms that the requested participant is both approved and an accepted friend.
  if (safeContactUid) {
    if (!Array.isArray(plan.safeContactUids) || !plan.safeContactUids.includes(safeContactUid)) {
      throw new HttpsError("permission-denied", "Share the safety plan with this safe contact first.");
    }
    const friends = await db.collection("friendLists").doc(caller.uid).collection("friends")
        .where("friendUid", "==", safeContactUid).limit(1).get();
    if (friends.empty) throw new HttpsError("permission-denied", "Only an accepted safe contact can join.");
  }
  const conversationId = plan.consultationConversationId;
  if (!conversationId) throw new HttpsError("failed-precondition", "Request a new plan consultation first.");
  await saveSupportConversation({...plan, conversationId, supportOrigin: PLAN_CONSULT}, safeContactUid);
  await reference.set({supportSafeContactUid: safeContactUid, updatedAt: Date.now()}, {merge: true});
  return {conversationId, safeContactUid};
});

/** Adds one administrator to an assigned staff member's existing support conversation. */
exports.safetySupportAdmin = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF") throw new HttpsError("permission-denied", "Assigned staff only.");
  const conversationId = typeof request.data?.conversationId === "string" ? request.data.conversationId : "";
  const conversationReference = db.collection("conversations").doc(conversationId);
  const conversationSnapshot = await conversationReference.get();
  if (!conversationSnapshot.exists || conversationSnapshot.get("staffUid") !== caller.uid) {
    throw new HttpsError("permission-denied", "Only the assigned staff member can request admin help.");
  }
  const conversation = conversationSnapshot.data();
  const candidates = [];
  // Builds the list from trusted admin claims and completed profiles.
  for (const user of await listEveryUser()) {
    if (user.disabled || user.customClaims?.role !== "ADMIN") continue;
    const profile = await readProfile(user.uid);
    candidates.push({uid: user.uid, name: profile?.fullName || user.displayName || "Administrator"});
  }
  if (!candidates.length) throw new HttpsError("failed-precondition", "No administrator is available.");
  const selected = candidates.find(admin => admin.uid === conversation.adminUid) ||
    candidates[Math.floor(Math.random() * candidates.length)];
  const origin = conversation.supportOrigin === PLAN_CONSULT ? PLAN_CONSULT : SUPPORT;
  const sourceReference = origin === PLAN_CONSULT ? planReference(conversation.ownerUid) :
    conversation.requestSource === "GROUP_CHAT" && conversation.sourceConversationId ?
      db.collection("conversations").doc(conversation.sourceConversationId) :
      db.collection("supportRequests").doc(conversation.sourceConversationId || conversation.ownerUid);
  // Group-created support tickets keep organization details out of the original personal chat.
  if (conversation.requestSource !== "GROUP_CHAT") {
    await sourceReference.set({adminUid: selected.uid, adminName: selected.name, updatedAt: Date.now()}, {merge: true});
  }
  const personalParticipantUids = (conversation.participantIds || []).filter(uid =>
    ![conversation.ownerUid, conversation.staffUid, conversation.adminUid].includes(uid));
  await saveSupportConversation({
    ...conversation, adminUid: selected.uid, adminName: selected.name,
    conversationId, supportOrigin: origin, personalParticipantUids,
  });
  return {conversationId, adminUid: selected.uid, adminName: selected.name};
});

/** Adds one accepted trusted contact without replacing the conversation or its encrypted history. */
exports.conversationAddParticipant = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  const conversationId = typeof request.data?.conversationId === "string" ? request.data.conversationId : "";
  const participantUid = typeof request.data?.participantUid === "string" ? request.data.participantUid : "";
  if (!conversationId || !participantUid || participantUid === caller.uid) {
    throw new HttpsError("invalid-argument", "Choose another account to add.");
  }
  const reference = db.collection("conversations").doc(conversationId);
  const snapshot = await reference.get();
  if (!snapshot.exists) throw new HttpsError("not-found", "Conversation not found.");
  const conversation = snapshot.data();
  const participantIds = Array.isArray(conversation.participantIds) ? conversation.participantIds : [];
  if (!participantIds.includes(caller.uid)) {
    throw new HttpsError("permission-denied", "Only a conversation member can add someone.");
  }
  if (participantIds.includes(participantUid)) return {conversationId, participantUid};
  if (conversation.closedAt) {
    throw new HttpsError("failed-precondition", "This conversation cannot add another person.");
  }

  // The add button only works for an accepted contact, never a directory search result.
  const friend = await db.collection("friendLists").doc(caller.uid).collection("friends")
      .where("friendUid", "==", participantUid).limit(1).get();
  if (friend.empty) throw new HttpsError("permission-denied", "Only an accepted contact can join.");
  // Account roles are protected Firebase Auth data. Profile files do not always contain a role.
  const targetUser = await auth.getUser(participantUid);
  const targetProfile = await readProfile(participantUid);
  const targetRole = roleFromUser(targetUser, targetProfile);
  if (!["USER", "SAFE_CONTACT"].includes(targetRole)) {
    throw new HttpsError("permission-denied", "Only a personal or trusted-contact account can be added.");
  }

  if (conversation.type === "SUPPORT") {
    if (!["USER", "SAFE_CONTACT"].includes(caller.role) || conversation.ownerUid !== caller.uid) {
      throw new HttpsError("permission-denied", "Only the person who requested support can add a contact.");
    }
  } else if (!["USER", "SAFE_CONTACT"].includes(caller.role)) {
    throw new HttpsError("permission-denied", "Organization accounts cannot change personal group members.");
  }

  const updatedIds = [...participantIds, participantUid];
  const updatedNames = await participantNames(updatedIds);
  const update = {
    participantIds: updatedIds,
    participantUsernames: updatedNames,
    updatedAt: FieldValue.serverTimestamp(),
  };
  // The person who first expands a direct chat becomes the permanent group owner.
  if (conversation.type !== "SUPPORT") {
    update.type = "GROUP";
    if (conversation.type !== "GROUP" || !conversation.ownerUid) update.ownerUid = caller.uid;
  }
  await reference.update(update);
  return {conversationId, participantUid};
});

/** Lets a group owner remove another member while keeping at least two people in the chat. */
exports.conversationRemoveParticipant = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  const conversationId = typeof request.data?.conversationId === "string" ? request.data.conversationId : "";
  const participantUid = typeof request.data?.participantUid === "string" ? request.data.participantUid : "";
  if (!conversationId || !participantUid || participantUid === caller.uid) {
    throw new HttpsError("invalid-argument", "Choose another group member to remove.");
  }
  const reference = db.collection("conversations").doc(conversationId);
  await db.runTransaction(async transaction => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) throw new HttpsError("not-found", "Conversation not found.");
    const conversation = snapshot.data();
    const updatedIds = validateGroupRemoval(conversation, caller.uid, participantUid);
    const updatedNames = Object.fromEntries(updatedIds.map(uid => [
      uid,
      conversation.participantUsernames?.[uid] || uid,
    ]));
    transaction.update(reference, {
      participantIds: updatedIds,
      participantUsernames: updatedNames,
      updatedAt: FieldValue.serverTimestamp(),
    });
  });
  return {conversationId, participantUid};
});

/** Turns a personal chat into a separate support ticket without changing the original chat. */
exports.conversationRequestStaff = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (!["USER", "SAFE_CONTACT"].includes(caller.role)) {
    throw new HttpsError("permission-denied", "Only personal accounts can request staff for a chat.");
  }
  const sourceConversationId = typeof request.data?.conversationId === "string" ?
    request.data.conversationId : "";
  if (!sourceConversationId) throw new HttpsError("invalid-argument", "Choose a conversation first.");
  const sourceReference = db.collection("conversations").doc(sourceConversationId);
  const sourceSnapshot = await sourceReference.get();
  if (!sourceSnapshot.exists) throw new HttpsError("not-found", "Conversation not found.");
  const source = sourceSnapshot.data();
  const personalParticipantUids = Array.isArray(source.participantIds) ? source.participantIds : [];
  if (!["DIRECT", "GROUP"].includes(source.type) || source.closedAt ||
      !personalParticipantUids.includes(caller.uid)) {
    throw new HttpsError("failed-precondition", "Only an open personal chat can request staff.");
  }
  if (personalParticipantUids.length < 2) {
    throw new HttpsError("failed-precondition", "A staff chat needs at least two personal accounts.");
  }
  // Every existing member must be a user or safe contact before staff can join the new chat.
  for (const uid of personalParticipantUids) {
    const user = await auth.getUser(uid);
    const role = roleFromUser(user, await readProfile(uid));
    if (!["USER", "SAFE_CONTACT"].includes(role)) {
      throw new HttpsError("failed-precondition", "This chat already contains an organization account.");
    }
  }
  const staffCandidates = await eligibleStaffCandidates();
  if (!staffCandidates.length) throw new HttpsError("failed-precondition", "No staff member is available.");
  const conversationId = supportConversationId(caller.uid, SUPPORT);
  const names = await participantNames([...personalParticipantUids, ...staffCandidates.map(({uid}) => uid)]);
  const selected = await db.runTransaction(async transaction => {
    const settings = [];
    for (const staff of staffCandidates) {
      const reference = db.collection("safetyStaff").doc(staff.uid);
      settings.push({...staff, reference, ...(await transaction.get(reference)).data()});
    }
    const staff = chooseStaff(settings);
    if (!staff) throw new HttpsError("failed-precondition", "No staff member is available.");
    const ticketNumber = await reserveSupportTicketNumber(transaction);
    const participantIds = [...personalParticipantUids, staff.uid];
    transaction.set(staff.reference, {activeCount: (staff.activeCount || 0) + 1}, {merge: true});
    transaction.set(db.collection("conversations").doc(conversationId), {
      type: "SUPPORT",
      ownerUid: caller.uid,
      staffUid: staff.uid,
      adminUid: "",
      supportOrigin: SUPPORT,
      supportTag: SUPPORT,
      requestSource: "GROUP_CHAT",
      sourceConversationId,
      friendshipId: conversationId,
      participantIds,
      participantUsernames: Object.fromEntries(participantIds.map(uid => [uid, names[uid] || uid])),
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
      lastMessage: "",
      lastMessageId: "",
      lastSenderUid: "",
      ticketNumber,
    });
    transaction.update(sourceReference, {
      supportConversationId: conversationId,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return staff;
  });
  return {conversationId, staffUid: selected.uid};
});

/** Archives a support conversation for everyone when its assigned staff member or administrator closes it. */
exports.staffSupportClose = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (!["STAFF", "ADMIN"].includes(caller.role)) {
    throw new HttpsError("permission-denied", "Organization support accounts only.");
  }
  const conversationId = typeof request.data?.conversationId === "string" ? request.data.conversationId : "";
  if (!/^(plan-consult|support)-[A-Za-z0-9_-]{1,300}$/.test(conversationId)) {
    throw new HttpsError("invalid-argument", "Invalid support conversation.");
  }
  const reference = db.collection("conversations").doc(conversationId);
  // Archives the chat, closes its ticket, and updates the staff workload together.
  await db.runTransaction(async transaction => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists || snapshot.get("type") !== "SUPPORT") {
      throw new HttpsError("permission-denied", "Support conversation not found.");
    }
    const conversation = snapshot.data();
    if (!canCloseSupportConversation(conversation, caller)) {
      throw new HttpsError("permission-denied", "Only organization support in this chat can close it.");
    }
    if (snapshot.get("closedAt")) return;
    const sourceReference = conversation.supportOrigin === PLAN_CONSULT ?
      planReference(conversation.ownerUid) :
      conversation.requestSource === "GROUP_CHAT" && conversation.sourceConversationId ?
        db.collection("conversations").doc(conversation.sourceConversationId) :
        db.collection("supportRequests").doc(conversation.sourceConversationId || conversation.ownerUid);
    if (!conversation.staffUid) throw new HttpsError("failed-precondition", "This ticket has no assigned staff member.");
    const staffReference = db.collection("safetyStaff").doc(conversation.staffUid);
    const sourceSnapshot = await transaction.get(sourceReference);
    const staffSnapshot = await transaction.get(staffReference);
    transaction.update(reference, {
      closedAt: FieldValue.serverTimestamp(),
      completedAt: FieldValue.serverTimestamp(),
      ticketStatus: "COMPLETED",
      updatedAt: FieldValue.serverTimestamp(),
    });
    // Clears only the ticket that still points to this exact conversation.
    if (sourceSnapshot.exists) {
      const source = sourceSnapshot.data();
      const sourceConversationId = conversation.supportOrigin === PLAN_CONSULT ?
        source.consultationConversationId : conversation.requestSource === "GROUP_CHAT" ?
          source.supportConversationId : source.conversationId;
      if (sourceConversationId === conversationId) {
        if (conversation.supportOrigin === PLAN_CONSULT) {
          transaction.set(sourceReference, {
            consultationRequested: false, staffUid: "", staffName: "", adminUid: "", adminName: "",
            consultationConversationId: "",
            updatedAt: Date.now(),
          }, {merge: true});
        } else if (conversation.requestSource === "GROUP_CHAT") {
          transaction.set(sourceReference, {
            supportConversationId: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp(),
          }, {merge: true});
        } else {
          transaction.set(sourceReference, {requested: false, updatedAt: Date.now()}, {merge: true});
        }
      }
    }
    const activeCount = staffSnapshot.data()?.activeCount || 0;
    transaction.set(staffReference, {activeCount: Math.max(0, activeCount - 1)}, {merge: true});
  });
  return {conversationId};
});

module.exports.saveSupportConversation = saveSupportConversation;
module.exports.closeSupportConversation = closeSupportConversation;
module.exports.supportConversationId = supportConversationId;
module.exports.PLAN_CONSULT = PLAN_CONSULT;
module.exports.SUPPORT = SUPPORT;
module.exports.eligibleStaffCandidates = eligibleStaffCandidates;
module.exports.validateGroupRemoval = validateGroupRemoval;
module.exports.canCloseSupportConversation = canCloseSupportConversation;
