// Users and staff: opens regular support tickets separately from plan consultations.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller} = require("./safetyPlanAccess");
const {saveSupportConversation, closeSupportConversation, supportConversationId, SUPPORT} =
  require("./supportGroups");

async function candidates() {
  const result = [];
  for (const user of await listEveryUser()) {
    if (user.disabled || user.customClaims?.role !== "STAFF") continue;
    const profile = await readProfile(user.uid);
    if (staffEligible(user, profile)) result.push({uid: user.uid, name: profile.fullName});
  }
  return result;
}

exports.staffSupportRequest = onCall({invoker: "public"}, async request => {
  const caller = await safetyCaller(request);
  const action = request.data?.action;
  if (caller.role !== "USER" || !["request", "stop"].includes(action)) {
    throw new HttpsError("permission-denied", "Owner only.");
  }
  const reference = db.collection("supportRequests").doc(caller.uid);
  if (action === "stop") {
    const current = (await reference.get()).data();
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
    await closeSupportConversation(caller.uid, SUPPORT);
    return {ticket: {ownerUid: caller.uid, requested: false, staffUid: "", conversationId: ""}};
  }

  const staffCandidates = await candidates();
  const ticket = await db.runTransaction(async transaction => {
    const current = (await transaction.get(reference)).data();
    if (current?.requested && current.staffUid) return current;
    const references = new Map(staffCandidates.map(staff => [
      staff.uid, db.collection("safetyStaff").doc(staff.uid),
    ]));
    const settings = new Map();
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
      conversationId: supportConversationId(caller.uid, SUPPORT), updatedAt: Date.now(),
    };
    transaction.set(reference, created);
    return created;
  });
  if (ticket.staffUid) await saveSupportConversation(ticket, "", true);
  return {ticket};
});
