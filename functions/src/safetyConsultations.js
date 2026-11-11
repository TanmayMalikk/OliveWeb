// Users and support staff: opens and updates safety-plan consultations.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db, FieldValue} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller, planReference} = require("./safetyPlanAccess");
const {saveSupportConversation, supportConversationId, PLAN_CONSULT} = require("./supportGroups");
const {reserveSupportTicketNumber} = require("./supportTicketNumbers");
exports.safetyPlanConsult = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  const action = request.data?.action;
  if (caller.role !== "USER" || !["request", "stop"].includes(action)) {
    throw new HttpsError("permission-denied", "Owner only.");
  }
  const reference = planReference(caller.uid);
  const newConversationId = supportConversationId(caller.uid, PLAN_CONSULT);
  const candidates = [];
  if (action === "request") {
    for (const user of await listEveryUser()) {
      if (user.disabled || user.customClaims?.role !== "STAFF") continue;
      const profile = await readProfile(user.uid);
      if (staffEligible(user, profile)) candidates.push({uid: user.uid, name: profile.fullName});
    }
  }
  const result = await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) throw new HttpsError("failed-precondition", "Sync the plan before sharing.");
    const plan = snapshot.data();
    const wasRequested = plan.consultationRequested === true;
    const consultationRequested = action === "request";
    const assignmentNeeded = consultationRequested;
    const references = new Map(candidates.map((staff) => [staff.uid, db.collection("safetyStaff").doc(staff.uid)]));
    if (plan.staffUid && !references.has(plan.staffUid)) references.set(plan.staffUid, db.collection("safetyStaff").doc(plan.staffUid));
    const settings = new Map();
    for (const [uid, ref] of references) settings.set(uid, (await transaction.get(ref)).data() || {});
    const current = plan.staffUid ? {uid: plan.staffUid, name: plan.staffName || "Staff member"} : null;
    const selected = assignmentNeeded ? current || chooseStaff(candidates.map((staff) => ({...staff, ...settings.get(staff.uid)}))) : null;
    const consultationConversationId = consultationRequested ?
      (wasRequested && plan.consultationConversationId ? plan.consultationConversationId : newConversationId) :
      (plan.consultationConversationId || "");
    const conversation = consultationConversationId ?
      await transaction.get(db.collection("conversations").doc(consultationConversationId)) : null;
    // An active request keeps its number. Every later consultation gets the next number.
    const ticketNumber = consultationRequested ?
      (wasRequested && Number.isSafeInteger(plan.ticketNumber) && plan.ticketNumber >= 0 ?
        plan.ticketNumber : await reserveSupportTicketNumber(transaction)) :
      (Number.isSafeInteger(plan.ticketNumber) ? plan.ticketNumber : -1);
    if (plan.staffUid && plan.staffUid !== selected?.uid) {
      transaction.set(references.get(plan.staffUid), {activeCount: Math.max(0, (settings.get(plan.staffUid)?.activeCount || 0) - 1)}, {merge: true});
    }
    if (selected && selected.uid !== plan.staffUid) transaction.set(references.get(selected.uid), {activeCount: (selected.activeCount || 0) + 1}, {merge: true});
    const updated = {...plan, staffUid: selected?.uid || "", staffName: "",
      adminUid: assignmentNeeded ? plan.adminUid || "" : "",
      adminName: assignmentNeeded ? plan.adminName || "" : "",
      ...(consultationRequested && !wasRequested ? {plansCompletedAt: 0} : {}),
      consultationRequested, consultationConversationId, ticketNumber, updatedAt: Date.now()};
    if (consultationRequested) await saveSupportConversation(
        {...updated, conversationId: consultationConversationId, supportOrigin: PLAN_CONSULT},
        updated.supportSafeContactUid || "", false, {transaction, snapshot: conversation});
    else if (action === "stop" && conversation?.exists && !conversation.get("closedAt")) {
      transaction.update(conversation.ref, {
        closedAt: FieldValue.serverTimestamp(), completedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(), ticketStatus: "COMPLETED",
      });
    }
    transaction.set(reference, updated);
    return {plan: updated};
  });
  return result;
});
