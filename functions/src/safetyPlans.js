const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebase");
const {mergeFields} = require("./safetyPlanPolicy");
const {safetyCaller, planReference, canEdit} = require("./safetyPlanAccess");
exports.safetyPlanSync = onCall(async (request) => {
  const caller = await safetyCaller(request);
  const ownerUid = request.data?.ownerUid;
  const reference = planReference(ownerUid);
  const defaults = request.data?.defaults || {};
  const changes = request.data?.changes || [];
  return db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    const old = snapshot.data();
    if (!canEdit(caller, old, ownerUid)) throw new HttpsError("permission-denied", "Plan is no longer shared with this account.");
    const {fields, conflicts} = mergeFields(old?.fields || {}, caller.uid === ownerUid ? defaults : {}, changes);
    const changed = !old || Object.keys(fields).length !== Object.keys(old.fields || {}).length ||
      Object.entries(fields).some(([key, field]) => field !== old.fields?.[key]);
    const plan = changed ? {...(old || {ownerUid, ownerName: caller.name, staffUid: "", staffName: "", consultationRequested: false}), fields, updatedAt: Date.now()} : old;
    if (changed) transaction.set(reference, plan);
    return {plan, conflicts};
  });
});
exports.safetyPlanConsultations = onCall(async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF") throw new HttpsError("permission-denied", "Staff only.");
  const snapshot = await db.collection("safetyPlans").where("staffUid", "==", caller.uid).get();
  const settings = await db.collection("safetyStaff").doc(caller.uid).get();
  return {available: settings.data()?.available !== false, plans: snapshot.docs.map((document) => ({ownerUid: document.id, ownerName: document.data().ownerName || "", updatedAt: document.data().updatedAt}))};
});
exports.safetyPlanAvailability = onCall(async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF" || typeof request.data?.available !== "boolean") throw new HttpsError("permission-denied", "Staff only.");
  await db.collection("safetyStaff").doc(caller.uid).set({available: request.data.available}, {merge: true});
  return {available: request.data.available};
});
