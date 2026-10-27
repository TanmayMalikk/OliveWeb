// Builds the private support conversation shared by a user, assigned staff, and one safe contact.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db, FieldValue} = require("./firebase");
const {listEveryUser, readProfile} = require("./accountStorage");
const {safetyCaller, planReference} = require("./safetyPlanAccess");

function supportConversationId(ownerUid) { return `support-${ownerUid}`; }

async function participantNames(participantIds) {
  const profiles = await Promise.all(participantIds.map(readProfile));
  return Object.fromEntries(participantIds.map((uid, index) => [uid, profiles[index]?.username || uid]));
}

async function saveSupportConversation(plan, safeContactUid = "") {
  if (!plan?.ownerUid || !plan?.staffUid) return;
  const participantIds = [...new Set([plan.ownerUid, plan.staffUid, safeContactUid, plan.adminUid].filter(Boolean))];
  if (participantIds.length < 2 || participantIds.length > 4) {
    throw new HttpsError("failed-precondition", "A support chat can have at most four people.");
  }
  const participantUsernames = await participantNames(participantIds);
  const reference = db.collection("conversations").doc(supportConversationId(plan.ownerUid));
  const current = await reference.get();
  const membershipChanged = current.exists &&
    JSON.stringify(current.data()?.participantIds || []) !== JSON.stringify(participantIds);
  const value = {
    type: "SUPPORT", ownerUid: plan.ownerUid, staffUid: plan.staffUid, adminUid: plan.adminUid || "",
    friendshipId: supportConversationId(plan.ownerUid), participantIds, participantUsernames,
    updatedAt: FieldValue.serverTimestamp(), lastMessage: current.data()?.lastMessage || "",
    lastMessageId: current.data()?.lastMessageId || "", lastSenderUid: current.data()?.lastSenderUid || "",
  };
  if (!current.exists) value.createdAt = FieldValue.serverTimestamp();
  if (membershipChanged) {
    value.roomKeys = FieldValue.delete();
    value.participantPublicKeys = FieldValue.delete();
  }
  await reference.set(value, {merge: true});
}

async function closeSupportConversation(ownerUid) {
  await db.recursiveDelete(db.collection("conversations").doc(supportConversationId(ownerUid)));
}

exports.safetySupportContact = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "USER") throw new HttpsError("permission-denied", "Owner only.");
  const safeContactUid = typeof request.data?.safeContactUid === "string" ? request.data.safeContactUid : "";
  const reference = planReference(caller.uid);
  const snapshot = await reference.get();
  if (!snapshot.exists) throw new HttpsError("failed-precondition", "Open the safety plan first.");
  const plan = snapshot.data();
  if (!plan.staffUid || plan.consultationRequested !== true) {
    throw new HttpsError("failed-precondition", "Request staff support before creating the support chat.");
  }
  if (safeContactUid) {
    if (!Array.isArray(plan.safeContactUids) || !plan.safeContactUids.includes(safeContactUid)) {
      throw new HttpsError("permission-denied", "Share the safety plan with this safe contact first.");
    }
    const friends = await db.collection("friendLists").doc(caller.uid).collection("friends")
        .where("friendUid", "==", safeContactUid).limit(1).get();
    if (friends.empty) throw new HttpsError("permission-denied", "Only an accepted safe contact can join.");
  }
  await saveSupportConversation(plan, safeContactUid);
  await reference.set({supportSafeContactUid: safeContactUid, updatedAt: Date.now()}, {merge: true});
  return {conversationId: supportConversationId(caller.uid), safeContactUid};
});

/** Adds one administrator to an assigned staff member's existing support conversation. */
exports.safetySupportAdmin = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF") throw new HttpsError("permission-denied", "Assigned staff only.");
  const reference = planReference(request.data?.ownerUid);
  const candidates = [];
  for (const user of await listEveryUser()) {
    if (user.disabled || user.customClaims?.role !== "ADMIN") continue;
    const profile = await readProfile(user.uid);
    candidates.push({uid: user.uid, name: profile?.fullName || user.displayName || "Administrator"});
  }
  if (!candidates.length) throw new HttpsError("failed-precondition", "No administrator is available.");
  const result = await db.runTransaction(async transaction => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) throw new HttpsError("not-found", "The support request was not found.");
    const plan = snapshot.data();
    if (!plan.consultationRequested || plan.staffUid !== caller.uid) {
      throw new HttpsError("permission-denied", "Only the assigned staff member can request admin help.");
    }
    const selected = candidates.find(admin => admin.uid === plan.adminUid) ||
      candidates[Math.floor(Math.random() * candidates.length)];
    const updated = {...plan, adminUid: selected.uid, adminName: selected.name, updatedAt: Date.now()};
    transaction.set(reference, updated);
    return updated;
  });
  await saveSupportConversation(result, result.supportSafeContactUid || "");
  return {conversationId: supportConversationId(result.ownerUid), adminUid: result.adminUid,
    adminName: result.adminName};
});

module.exports.saveSupportConversation = saveSupportConversation;
module.exports.closeSupportConversation = closeSupportConversation;
