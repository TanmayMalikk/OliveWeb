// Contains the safety plans code for this part of Olive.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebase");
const {mergeFields} = require("./safetyPlanPolicy");
const {safetyCaller, planReference, canEdit, canRead} = require("./safetyPlanAccess");
exports.safetyPlanSync = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  const ownerUid = request.data?.ownerUid;
  const reference = planReference(ownerUid);
  const defaults = request.data?.defaults || {};
  const changes = request.data?.changes || [];
  return db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    const old = snapshot.data();
    if (!canRead(caller, old, ownerUid)) throw new HttpsError("permission-denied", "Plan is no longer shared with this account.");
    if (changes.length && !canEdit(caller, old, ownerUid)) throw new HttpsError("permission-denied", "This shared plan is read only.");
    const {fields, conflicts} = mergeFields(old?.fields || {}, caller.uid === ownerUid ? defaults : {}, changes);
    const changed = !old || Object.keys(fields).length !== Object.keys(old.fields || {}).length ||
      Object.entries(fields).some(([key, field]) => field !== old.fields?.[key]);
    const plan = changed ? {...(old || {ownerUid, ownerName: caller.name, staffUid: "", staffName: "", consultationRequested: false,
      safeContactUids: [], safeContactNames: {}, supportSafeContactUid: ""}), fields, updatedAt: Date.now()} : old;
    if (changed) transaction.set(reference, plan);
    return {plan, conflicts};
  });
});
exports.safetyPlanShareContacts = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  const contacts = Array.isArray(request.data?.contacts) ? request.data.contacts : [];
  if (caller.role !== "USER" || contacts.length > 10) throw new HttpsError("permission-denied", "Owner only.");
  const clean = contacts.map((contact) => ({
    uid: typeof contact?.uid === "string" ? contact.uid : "",
    friendshipId: typeof contact?.friendshipId === "string" ? contact.friendshipId : "",
    name: typeof contact?.name === "string" ? contact.name.trim().slice(0, 120) : "",
  }));
  if (new Set(clean.map((contact) => contact.uid)).size !== clean.length || clean.some((contact) =>
    !/^[A-Za-z0-9_-]{1,128}$/.test(contact.uid) || !/^[A-Za-z0-9_-]{1,300}$/.test(contact.friendshipId))) {
    throw new HttpsError("invalid-argument", "Invalid contacts.");
  }
  const friendships = await Promise.all(clean.map((contact) =>
    db.collection("friendLists").doc(caller.uid).collection("friends").doc(contact.friendshipId).get()));
  if (friendships.some((snapshot, index) => !snapshot.exists || snapshot.data()?.friendUid !== clean[index].uid)) {
    throw new HttpsError("permission-denied", "Only accepted friends can receive a plan.");
  }
  const reference = planReference(caller.uid);
  return db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) throw new HttpsError("failed-precondition", "Open the plan before sharing it.");
    const plan = snapshot.data();
    const updated = {...plan, safeContactUids: clean.map((contact) => contact.uid),
      safeContactNames: Object.fromEntries(clean.map((contact) => [contact.uid, contact.name])), updatedAt: Date.now()};
    transaction.set(reference, updated);
    return {plan: updated};
  });
});
exports.safetyPlansSharedWithMe = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "SAFE_CONTACT") throw new HttpsError("permission-denied", "Safe contacts only.");
  const snapshot = await db.collection("safetyPlans").where("safeContactUids", "array-contains", caller.uid).get();
  return {plans: snapshot.docs.map((document) => ({ownerUid: document.id,
    ownerName: document.data().ownerName || "", updatedAt: document.data().updatedAt || 0}))};
});
exports.safetyPlanConsultations = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (!["STAFF", "ADMIN"].includes(caller.role)) throw new HttpsError("permission-denied", "Organization access only.");
  const snapshot = caller.role === "ADMIN" ? await db.collection("safetyPlans").get() :
    await db.collection("safetyPlans").where("staffUid", "==", caller.uid).get();
  const settings = caller.role === "STAFF" ? await db.collection("safetyStaff").doc(caller.uid).get() : null;
  return {available: caller.role === "STAFF" ? settings.data()?.available !== false : true,
    plans: snapshot.docs.map((document) => ({ownerUid: document.id, ownerName: document.data().ownerName || "", updatedAt: document.data().updatedAt}))};
});
exports.safetyPlanAvailability = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF" || typeof request.data?.available !== "boolean") throw new HttpsError("permission-denied", "Staff only.");
  await db.collection("safetyStaff").doc(caller.uid).set({available: request.data.available}, {merge: true});
  return {available: request.data.available};
});
