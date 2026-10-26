// Contains the safety consultations code for this part of Olive.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebase");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller, planReference} = require("./safetyPlanAccess");
const {saveSupportConversation} = require("./supportGroups");
exports.safetyPlanConsult = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "USER" || !["request", "stop"].includes(request.data?.action)) throw new HttpsError("permission-denied", "Owner only.");
  const reference = planReference(caller.uid);
  const candidates = [];
  if (request.data.action === "request") {
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
    const references = new Map(candidates.map((staff) => [staff.uid, db.collection("safetyStaff").doc(staff.uid)]));
    if (plan.staffUid && !references.has(plan.staffUid)) references.set(plan.staffUid, db.collection("safetyStaff").doc(plan.staffUid));
    const settings = new Map();
    for (const [uid, ref] of references) settings.set(uid, (await transaction.get(ref)).data() || {});
    if (request.data.action === "request" && candidates.some((staff) => staff.uid === plan.staffUid)) return {plan};
    const selected = request.data.action === "request" ? chooseStaff(candidates.map((staff) => ({...staff, ...settings.get(staff.uid)}))) : null;
    if (plan.staffUid && plan.staffUid !== selected?.uid) {
      transaction.set(references.get(plan.staffUid), {activeCount: Math.max(0, (settings.get(plan.staffUid)?.activeCount || 0) - 1)}, {merge: true});
    }
    if (selected && selected.uid !== plan.staffUid) transaction.set(references.get(selected.uid), {activeCount: (selected.activeCount || 0) + 1}, {merge: true});
    const updated = {...plan, staffUid: selected?.uid || "", staffName: selected?.name || "",
      consultationRequested: request.data.action === "request", updatedAt: Date.now()};
    transaction.set(reference, updated);
    return {plan: updated};
  });
  if (result.plan.staffUid) await saveSupportConversation(result.plan, result.plan.supportSafeContactUid || "");
  return result;
});
