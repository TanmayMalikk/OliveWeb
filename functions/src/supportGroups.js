// Builds the private support conversation shared by a user, assigned staff, and one safe contact.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db, FieldValue} = require("./firebase");
const {readProfile} = require("./accountStorage");
const {safetyCaller, planReference} = require("./safetyPlanAccess");

function supportConversationId(ownerUid) { return `support-${ownerUid}`; }

async function participantNames(participantIds) {
  const profiles = await Promise.all(participantIds.map(readProfile));
  return Object.fromEntries(participantIds.map((uid, index) => [uid, profiles[index]?.username || uid]));
}

async function saveSupportConversation(plan, safeContactUid = "") {
  if (!plan?.ownerUid || !plan?.staffUid) return;
  const participantIds = [plan.ownerUid, plan.staffUid, safeContactUid].filter(Boolean);
  const participantUsernames = await participantNames(participantIds);
  const reference = db.collection("conversations").doc(supportConversationId(plan.ownerUid));
  const current = await reference.get();
  const membershipChanged = current.exists &&
    JSON.stringify(current.data()?.participantIds || []) !== JSON.stringify(participantIds);
  const value = {
    type: "SUPPORT", ownerUid: plan.ownerUid, staffUid: plan.staffUid,
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

module.exports.saveSupportConversation = saveSupportConversation;
