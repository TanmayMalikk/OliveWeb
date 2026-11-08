// Users and staff: opens regular support tickets separately from plan consultations.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller} = require("./safetyPlanAccess");
const {saveSupportConversation, closeSupportConversation, supportConversationId, SUPPORT} =
  require("./supportGroups");

// Finds staff accounts that are active and ready to receive support tickets.
async function candidates() {
  const result = [];
  // Checks trusted role claims and profile readiness for each account.
  for (const user of await listEveryUser()) {
    if (user.disabled || user.customClaims?.role !== "STAFF") continue;
    const profile = await readProfile(user.uid);
    if (staffEligible(user, profile)) result.push({uid: user.uid, name: profile.fullName});
  }
  return result;
}

// Starts or cancels a user's regular support ticket independently of consultations.
exports.staffSupportRequest = onCall({invoker: "public"}, async request => {
  const caller = await safetyCaller(request);
  const action = request.data?.action;
  if (caller.role !== "USER" || !["request", "stop"].includes(action)) {
    throw new HttpsError("permission-denied", "Owner only.");
  }
  const newConversationId = supportConversationId(caller.uid, SUPPORT);
  const requestedConversationId = typeof request.data?.conversationId === "string" ?
    request.data.conversationId : "";
  // New requests use their conversation ID as the ticket document ID, allowing several open tickets.
  // The owner UID fallback keeps cancellation compatible with tickets made by older app versions.
  const reference = db.collection("supportRequests").doc(
      action === "request" ? newConversationId : (requestedConversationId || caller.uid));
  // Cancels the active request, archives its chat, and releases the assigned staff slot.
  if (action === "stop") {
    const current = (await reference.get()).data();
    if (current && current.ownerUid !== caller.uid) {
      throw new HttpsError("permission-denied", "You can cancel only your own support ticket.");
    }
    if (current?.staffUid) {
      const staffReference = db.collection("safetyStaff").doc(current.staffUid);
      await db.runTransaction(async transaction => {
        const settings = (await transaction.get(staffReference)).data() || {};
        transaction.set(staffReference, {
          activeCount: Math.max(0, (settings.activeCount || 0) - 1),
        }, {merge: true});
        transaction.delete(reference);
      });
    } else await reference.delete();
    await closeSupportConversation(current?.conversationId || "");
    return {ticket: {ownerUid: caller.uid, requested: false, staffUid: "", conversationId: ""}};
  }

  const staffCandidates = await candidates();
  // Assigns the least-busy staff member and saves the new ticket atomically.
  const ticket = await db.runTransaction(async transaction => {
    const references = new Map(staffCandidates.map(staff => [
      staff.uid, db.collection("safetyStaff").doc(staff.uid),
    ]));
    const settings = new Map();
    // Loads each candidate's live assignment count inside the transaction.
    for (const [uid, staffReference] of references) {
      settings.set(uid, (await transaction.get(staffReference)).data() || {});
    }
    const selected = chooseStaff(staffCandidates.map(staff => ({...staff, ...settings.get(staff.uid)})));
    if (selected) transaction.set(references.get(selected.uid), {
      activeCount: (selected.activeCount || 0) + 1,
    }, {merge: true});
    const created = {
      ownerUid: caller.uid, ownerName: caller.name, requested: true,
      staffUid: selected?.uid || "", staffName: selected?.name || "",
      adminUid: "", adminName: "", supportOrigin: SUPPORT,
      requestSource: "SUPPORT_BUTTON", sourceConversationId: newConversationId,
      conversationId: newConversationId, updatedAt: Date.now(),
    };
    transaction.set(reference, created);
    return created;
  });
  if (ticket.staffUid) await saveSupportConversation(ticket, "", false);
  return {ticket};
});
