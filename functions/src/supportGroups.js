// Builds the private support conversation shared by a user, assigned staff, and one safe contact.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db, FieldValue} = require("./firebase");
const {listEveryUser, readProfile} = require("./accountStorage");
const {safetyCaller, planReference} = require("./safetyPlanAccess");

const PLAN_CONSULT = "PLAN_CONSULT";
const SUPPORT = "SUPPORT";
const NEEDS_ADMIN = "NEEDS_ADMIN";

function supportConversationId(ownerUid, origin = SUPPORT) {
  return `${origin === PLAN_CONSULT ? "plan-consult" : "support"}-${ownerUid}`;
}

async function participantNames(participantIds) {
  const profiles = await Promise.all(participantIds.map(readProfile));
  return Object.fromEntries(participantIds.map((uid, index) => [uid, profiles[index]?.username || uid]));
}

async function saveSupportConversation(plan, safeContactUid = "") {
  if (!plan?.ownerUid || !plan?.staffUid) return;
  const origin = plan.supportOrigin === PLAN_CONSULT ? PLAN_CONSULT : SUPPORT;
  const conversationId = plan.conversationId || supportConversationId(plan.ownerUid, origin);
  const participantIds = [...new Set([plan.ownerUid, plan.staffUid, safeContactUid, plan.adminUid].filter(Boolean))];
  if (participantIds.length < 2 || participantIds.length > 4) {
    throw new HttpsError("failed-precondition", "A support chat can have at most four people.");
  }
  const participantUsernames = await participantNames(participantIds);
  const reference = db.collection("conversations").doc(conversationId);
  const current = await reference.get();
  const membershipChanged = current.exists &&
    JSON.stringify(current.data()?.participantIds || []) !== JSON.stringify(participantIds);
  const value = {
    type: "SUPPORT", ownerUid: plan.ownerUid, staffUid: plan.staffUid, adminUid: plan.adminUid || "",
    supportOrigin: origin, supportTag: plan.adminUid ? NEEDS_ADMIN : origin,
    friendshipId: conversationId, participantIds, participantUsernames,
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

async function closeSupportConversation(ownerUid, origin = SUPPORT) {
  await db.recursiveDelete(db.collection("conversations").doc(supportConversationId(ownerUid, origin)));
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
    throw new HttpsError("failed-precondition", "Request a plan consultation before creating the support chat.");
  }
  if (safeContactUid) {
    if (!Array.isArray(plan.safeContactUids) || !plan.safeContactUids.includes(safeContactUid)) {
      throw new HttpsError("permission-denied", "Share the safety plan with this safe contact first.");
    }
    const friends = await db.collection("friendLists").doc(caller.uid).collection("friends")
        .where("friendUid", "==", safeContactUid).limit(1).get();
    if (friends.empty) throw new HttpsError("permission-denied", "Only an accepted safe contact can join.");
  }
  await saveSupportConversation({...plan, supportOrigin: PLAN_CONSULT}, safeContactUid);
  await reference.set({supportSafeContactUid: safeContactUid, updatedAt: Date.now()}, {merge: true});
  return {conversationId: supportConversationId(caller.uid, PLAN_CONSULT), safeContactUid};
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
    db.collection("supportRequests").doc(conversation.ownerUid);
  await sourceReference.set({adminUid: selected.uid, adminName: selected.name, updatedAt: Date.now()}, {merge: true});
  const safeContactUid = (conversation.participantIds || []).find(uid =>
    ![conversation.ownerUid, conversation.staffUid, conversation.adminUid].includes(uid)) || "";
  await saveSupportConversation({
    ...conversation, adminUid: selected.uid, adminName: selected.name,
    conversationId, supportOrigin: origin,
  }, safeContactUid);
  return {conversationId, adminUid: selected.uid, adminName: selected.name};
});

module.exports.saveSupportConversation = saveSupportConversation;
module.exports.closeSupportConversation = closeSupportConversation;
module.exports.supportConversationId = supportConversationId;
module.exports.PLAN_CONSULT = PLAN_CONSULT;
module.exports.SUPPORT = SUPPORT;
