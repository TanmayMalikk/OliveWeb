// Users and support staff: saves safety-plan edits and shares approved plans.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db} = require("./firebaseAdminClient");
const {readProfile} = require("./accountStorage");
const {roleFromUser, text} = require("./accountPolicy");
const {mergeFields} = require("./safetyPlanPolicy");
const {mergeChecklist} = require("./safetyChecklistMerge");
const {safetyCaller, planReference, canEdit, canRead} = require("./safetyPlanAccess");
const {repairPlanTicket} = require("./supportTicketRepair");
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
    const {fields, conflicts} = mergeFields(
        old?.fields || {}, caller.uid === ownerUid ? defaults : {}, changes, caller.uid);
    const changed = !old || Object.keys(fields).length !== Object.keys(old.fields || {}).length ||
      Object.entries(fields).some(([key, field]) => field !== old.fields?.[key]) ||
      JSON.stringify(pendingConflicts) !== JSON.stringify(old.pendingConflicts || {});
    const plan = changed ? {...(old || {ownerUid, ownerName: caller.name, staffUid: "", staffName: "", adminUid: "", adminName: "", consultationRequested: false,
      safeContactUids: [], safeContactNames: {}, supportSafeContactUid: ""}),
    fields, pendingConflicts, resolvedConflicts, updatedAt: Date.now()} : old;
    if (changed) transaction.set(reference, plan);
    return {plan, conflicts, conflictValues};
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
  const accounts = await Promise.all(clean.map(({uid}) => auth.getUser(uid)));
  if (accounts.some(account => account.disabled || roleFromUser(account) !== "SAFE_CONTACT")) {
    throw new HttpsError("permission-denied", "Only active safe contacts can receive a plan.");
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

// Gives a plan owner the public organization identities needed to wrap attachment keys on-device.
exports.safetyPlanMediaRecipients = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "USER") throw new HttpsError("permission-denied", "Owner only.");
  const plan = (await planReference(caller.uid).get()).data();
  if (!plan) throw new HttpsError("failed-precondition", "Open the plan before sharing files.");
  const recipients = new Set([...(plan.safeContactUids || []), plan.staffUid, plan.adminUid].filter(Boolean));
  const rows = await Promise.all([...recipients].map(async (uid) => {
    const profile = await readProfile(uid);
    const username = text(profile?.username, 24).toLowerCase();
    return /^[a-z0-9_]{4,24}$/.test(username) ? {uid, username} : null;
  }));
  return {recipients: rows.filter(Boolean)};
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
  const snapshot = caller.role === "ADMIN" ?
    await db.collection("safetyPlans").where("consultationRequested", "==", true).get() :
    await db.collection("safetyPlans").where("staffUid", "==", caller.uid).get();
  const settings = caller.role === "STAFF" ? await db.collection("safetyStaff").doc(caller.uid).get() : null;
  const requestedRows = snapshot.docs.filter(document => document.data().consultationRequested === true);
  for (const document of requestedRows) {
    if (!document.get("consultationConversationId") || !Number.isSafeInteger(document.get("ticketNumber"))) {
      await repairPlanTicket(document.id);
    }
  }
  const requestedPlans = requestedRows.length ? await db.getAll(...requestedRows.map(row => row.ref)) : [];
  const conversationIds = [...new Set(requestedPlans.map(document =>
    text(document.data().consultationConversationId, 300)).filter(Boolean))];
  const conversationSnapshots = conversationIds.length ?
    await db.getAll(...conversationIds.map(id => db.collection("conversations").doc(id))) : [];
  const conversations = new Map(conversationSnapshots.map(document => [document.id, document.data()]));
  const completedPlans = requestedPlans.filter(document => {
    const conversationId = text(document.data().consultationConversationId, 300);
    const conversation = conversations.get(conversationId);
    return conversation?.ticketStatus === "COMPLETED" || Boolean(conversation?.closedAt);
  });
  // Repairs older records so completed consultations are not scanned again on later refreshes.
  if (completedPlans.length) {
    for (let start = 0; start < completedPlans.length; start += 400) {
      const batch = db.batch();
      completedPlans.slice(start, start + 400).forEach(document => batch.set(document.ref, {
        consultationRequested: false,
        staffUid: "",
        staffName: "",
        adminUid: "",
        adminName: "",
        updatedAt: Date.now(),
      }, {merge: true}));
      await batch.commit();
    }
  }
  const completedOwnerUids = new Set(completedPlans.map(document => document.id));
  return {available: caller.role === "STAFF" ? settings.data()?.available !== false : true,
    plans: requestedPlans.filter(document => !completedOwnerUids.has(document.id))
        .map((document) => ({
          ownerUid: document.id,
          ownerName: document.data().ownerName || "",
          conversationId: document.data().consultationConversationId || "",
          ticketNumber: Number.isSafeInteger(document.data().ticketNumber) ? document.data().ticketNumber : -1,
          staffUid: document.data().staffUid || "",
          staffName: document.data().staffName || "",
          updatedAt: document.data().updatedAt,
        }))};
});
exports.safetyPlanAvailability = onCall({invoker: "public"}, async (request) => {
  const caller = await safetyCaller(request);
  if (caller.role !== "STAFF" || typeof request.data?.available !== "boolean") throw new HttpsError("permission-denied", "Staff only.");
  await db.collection("safetyStaff").doc(caller.uid).set({available: request.data.available}, {merge: true});
  return {available: request.data.available};
});
