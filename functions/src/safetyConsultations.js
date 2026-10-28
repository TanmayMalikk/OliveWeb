// Users and support staff: opens and updates safety-plan consultations.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller, planReference} = require("./safetyPlanAccess");
const {closeSupportConversation, saveSupportConversation, supportConversationId, PLAN_CONSULT} = require("./supportGroups");
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
    if (plan.staffUid && plan.staffUid !== selected?.uid) {
      transaction.set(references.get(plan.staffUid), {activeCount: Math.max(0, (settings.get(plan.staffUid)?.activeCount || 0) - 1)}, {merge: true});
    }
    if (selected && selected.uid !== plan.staffUid) transaction.set(references.get(selected.uid), {activeCount: (selected.activeCount || 0) + 1}, {merge: true});
    const consultationConversationId = consultationRequested ?
      (wasRequested && plan.consultationConversationId ? plan.consultationConversationId : newConversationId) :
      (plan.consultationConversationId || "");
    const updated = {...plan, staffUid: selected?.uid || "", staffName: selected?.name || "",
      adminUid: assignmentNeeded ? plan.adminUid || "" : "",
      adminName: assignmentNeeded ? plan.adminName || "" : "",
      consultationRequested, consultationConversationId, updatedAt: Date.now()};
    transaction.set(reference, updated);
    return {plan: updated, closedConversationId: action === "stop" ? consultationConversationId : ""};
  });
  if (result.plan.staffUid) await saveSupportConversation(
      {...result.plan, conversationId: result.plan.consultationConversationId, supportOrigin: PLAN_CONSULT},
      result.plan.supportSafeContactUid || "", false);
  else if (action === "stop") await closeSupportConversation(result.closedConversationId);
  return {plan: result.plan};
});
