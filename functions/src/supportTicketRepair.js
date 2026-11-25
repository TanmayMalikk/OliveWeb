// Repairs server-owned ticket metadata created before numbered consultation chats.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db} = require("./firebaseAdminClient");
const {readProfile} = require("./accountStorage");
const {chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller, planReference} = require("./safetyPlanAccess");
const {reserveSupportTicketNumber} = require("./supportTicketNumbers");
const {
  saveSupportConversation,
  PLAN_CONSULT,
  eligibleStaffCandidates,
  hasRegisteredMessagingKey,
} = require("./supportGroups");

const numbered = value => Number.isSafeInteger(value) && value >= 0;
const validUsername = value => /^[a-z0-9_]{4,24}$/.test(String(value || "").trim().toLowerCase());
const normalizedNames = value => Object.fromEntries(Object.entries(value || {})
    .map(([uid, name]) => [uid, String(name).trim().toLowerCase()]));

async function resolveMissingUsernames(participantIds, currentNames) {
  const resolved = {...currentNames};
  const missing = participantIds.filter(uid => !validUsername(resolved[uid]));
  await Promise.all(missing.map(async uid => {
    const profile = await readProfile(uid).catch(() => null);
    let candidate = String(profile?.username || "").trim().toLowerCase();
    if (!validUsername(candidate)) {
      const user = await auth.getUser(uid).catch(() => null);
      candidate = String(user?.displayName || "").trim().toLowerCase();
    }
    if (validUsername(candidate)) resolved[uid] = candidate;
  }));
  return resolved;
}

async function repairPlanTicket(ownerUid) {
  const planRef = db.collection("safetyPlans").doc(ownerUid);
  return db.runTransaction(async transaction => {
    const planSnapshot = await transaction.get(planRef);
    const plan = planSnapshot.data();
    if (!plan?.consultationRequested) return;
    let id = plan.consultationConversationId || "support-" + ownerUid;
    let reference = db.collection("conversations").doc(id);
    let chat = await transaction.get(reference);
    if (!plan.consultationConversationId && (!chat.exists ||
        (chat.get("supportOrigin") && chat.get("supportOrigin") !== PLAN_CONSULT) ||
        chat.get("staffUid") !== plan.staffUid || chat.get("closedAt"))) {
      id = "plan-consult-" + ownerUid + "-legacy";
      reference = db.collection("conversations").doc(id);
      chat = await transaction.get(reference);
    }
    if (chat.get("closedAt")) return;
    const ticketNumber = numbered(chat.get("ticketNumber")) ? chat.get("ticketNumber") :
      numbered(plan.ticketNumber) ? plan.ticketNumber : await reserveSupportTicketNumber(transaction);
    if (!chat.exists) {
      await saveSupportConversation({...plan, conversationId: id, ticketNumber, supportOrigin: PLAN_CONSULT},
          plan.supportSafeContactUid || "", false, {transaction, snapshot: chat});
    } else {
      const changes = {
        ticketNumber, supportOrigin: PLAN_CONSULT,
        supportTag: chat.get("adminUid") ? "NEEDS_ADMIN" : PLAN_CONSULT,
        friendshipId: id,
        participantUsernames: normalizedNames(chat.get("participantUsernames")),
        lastMessage: chat.get("lastMessage") || "", lastMessageId: chat.get("lastMessageId") || "",
      };
      if (Object.entries(changes).some(([key, value]) => JSON.stringify(chat.get(key)) !== JSON.stringify(value))) {
        transaction.set(reference, changes, {merge: true});
      }
    }
    if (plan.consultationConversationId !== id || plan.ticketNumber !== ticketNumber) {
      transaction.set(planRef, {consultationConversationId: id, ticketNumber, updatedAt: Date.now()}, {merge: true});
    }
    return {conversationId: id, ticketNumber};
  });
}

async function repairConversation(id) {
  const reference = db.collection("conversations").doc(id);
  const initial = await reference.get();
  if (!initial.exists || initial.get("type") !== "SUPPORT") return;
  if (initial.get("ownerUid")) await repairPlanTicket(initial.get("ownerUid"));
  const beforeTx = await reference.get();
  if (!beforeTx.exists) return;
  const beforeIds = Array.isArray(beforeTx.get("participantIds")) ? beforeTx.get("participantIds") : [];
  const resolvedNames = await resolveMissingUsernames(beforeIds, normalizedNames(beforeTx.get("participantUsernames")));

  // Reassigns uninitialized open tickets whose assigned staff member lacks a messaging key.
  const canCheckReassign = !beforeTx.get("closedAt") &&
    !beforeTx.get("lastMessageId") &&
    (!beforeTx.get("roomKeys") || Object.keys(beforeTx.get("roomKeys")).length === 0) &&
    Boolean(beforeTx.get("staffUid"));
  let readyStaffPool = [];
  if (canCheckReassign) {
    const oldStaffUid = beforeTx.get("staffUid");
    const staffReady = await hasRegisteredMessagingKey(oldStaffUid, resolvedNames[oldStaffUid]);
    if (!staffReady) {
      readyStaffPool = await eligibleStaffCandidates({strictKeyReady: true});
    }
  }

  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) return;
    const origin = snapshot.get("supportOrigin") || (id.startsWith("plan-consult-") ? PLAN_CONSULT : "SUPPORT");
    let participantIds = Array.isArray(snapshot.get("participantIds")) ? [...snapshot.get("participantIds")] : [];
    const participantUsernames = {
      ...normalizedNames(snapshot.get("participantUsernames")),
      ...Object.fromEntries(participantIds.filter(uid => resolvedNames[uid]).map(uid => [uid, resolvedNames[uid]])),
    };
    let staffUid = snapshot.get("staffUid") || "";
    const canReassign = readyStaffPool.length > 0 &&
      Boolean(staffUid) &&
      !snapshot.get("closedAt") &&
      !snapshot.get("lastMessageId") &&
      (!snapshot.get("roomKeys") || Object.keys(snapshot.get("roomKeys")).length === 0);

    let reassignment = null;
    if (canReassign) {
      const oldStaffRef = db.collection("safetyStaff").doc(staffUid);
      const oldStaffSnap = await transaction.get(oldStaffRef);
      const candidateSettings = [];
      for (const staff of readyStaffPool) {
        const ref = db.collection("safetyStaff").doc(staff.uid);
        const doc = await transaction.get(ref);
        candidateSettings.push({...staff, ref, ...(doc.data() || {})});
      }
      let sourceRef = origin === PLAN_CONSULT && snapshot.get("ownerUid") ?
        planReference(snapshot.get("ownerUid")) :
        origin === "SUPPORT" && snapshot.get("requestSource") !== "GROUP_CHAT" ?
          db.collection("supportRequests").doc(snapshot.get("sourceConversationId") || id) :
          null;
      let sourceSnap = sourceRef ? await transaction.get(sourceRef) : null;
      if (origin === "SUPPORT" && snapshot.get("requestSource") !== "GROUP_CHAT" &&
          !sourceSnap?.exists && !snapshot.get("sourceConversationId") && snapshot.get("ownerUid")) {
        const legacyRef = db.collection("supportRequests").doc(snapshot.get("ownerUid"));
        const legacySnap = await transaction.get(legacyRef);
        if (legacySnap.exists) {
          sourceRef = legacyRef;
          sourceSnap = legacySnap;
        }
      }
      const replacement = chooseStaff(candidateSettings);
      if (replacement && replacement.uid !== staffUid) {
        reassignment = {oldStaffUid: staffUid, oldStaffRef, oldStaffSnap, replacement, sourceRef, sourceSnap};
      }
    }

    if (reassignment) {
      const {oldStaffUid, oldStaffRef, oldStaffSnap, replacement, sourceRef, sourceSnap} = reassignment;
      const ownerUid = snapshot.get("ownerUid");
      const adminUid = snapshot.get("adminUid");
      participantIds = [...new Set(participantIds.map(uid => uid === oldStaffUid ? replacement.uid : uid))];
      if (!participantIds.includes(replacement.uid)) participantIds.push(replacement.uid);
      if (oldStaffUid !== ownerUid && oldStaffUid !== adminUid) delete participantUsernames[oldStaffUid];
      participantUsernames[replacement.uid] = replacement.username;
      staffUid = replacement.uid;
      transaction.set(
        oldStaffRef,
        {activeCount: Math.max(0, (oldStaffSnap.data()?.activeCount || 0) - 1)},
        {merge: true},
      );
      transaction.set(
        replacement.ref,
        {activeCount: (replacement.activeCount || 0) + 1},
        {merge: true},
      );
      if (sourceSnap?.exists) {
        const expectedConv = origin === PLAN_CONSULT ?
          sourceSnap.data()?.consultationConversationId :
          sourceSnap.data()?.conversationId;
        if (expectedConv === id) {
          transaction.set(
            sourceRef,
            {staffUid: replacement.uid, staffName: replacement.name, updatedAt: Date.now()},
            {merge: true},
          );
        }
      }
    }

    const changes = {
      ticketNumber: numbered(snapshot.get("ticketNumber")) ? snapshot.get("ticketNumber") :
        await reserveSupportTicketNumber(transaction),
      supportOrigin: origin,
      supportTag: snapshot.get("adminUid") ? "NEEDS_ADMIN" : origin,
      friendshipId: id,
      staffUid,
      participantIds,
      participantUsernames,
      lastMessage: snapshot.get("lastMessage") || "",
      lastMessageId: snapshot.get("lastMessageId") || "",
    };
    if (Object.entries(changes).some(([key, value]) => JSON.stringify(snapshot.get(key)) !== JSON.stringify(value))) {
      transaction.set(reference, changes, {merge: true});
    }
    return {
      participantIds: changes.participantIds,
      participantUsernames: changes.participantUsernames,
      ticketNumber: changes.ticketNumber,
    };
  });
}

exports.conversationPrepareKeys = onCall({invoker: "public"}, async request => {
  const caller = await safetyCaller(request);
  const id = request.data?.conversationId;
  if (typeof id !== "string" || !/^(support|plan-consult)-[A-Za-z0-9_-]{1,300}$/.test(id)) {
    throw new HttpsError("invalid-argument", "Invalid support ticket.");
  }
  const chat = await db.collection("conversations").doc(id).get();
  if (chat.get("type") !== "SUPPORT" || !chat.get("participantIds")?.includes(caller.uid)) {
    throw new HttpsError("permission-denied", "Only ticket participants can prepare encryption.");
  }
  return repairConversation(id);
});

exports.supportTicketsPrepare = onCall({invoker: "public"}, async request => {
  const caller = await safetyCaller(request);
  const plans = caller.role === "USER" ?
    await db.collection("safetyPlans").where("ownerUid", "==", caller.uid).get() :
    ["STAFF", "ADMIN"].includes(caller.role) ?
      await db.collection("safetyPlans").where(caller.role === "STAFF" ? "staffUid" : "adminUid", "==", caller.uid).get() : null;
  for (const plan of plans?.docs || []) {
    if (plan.get("consultationRequested") && (!plan.get("consultationConversationId") || !numbered(plan.get("ticketNumber")))) {
      await repairPlanTicket(plan.id);
    }
  }
  const chats = await db.collection("conversations").where("participantIds", "array-contains", caller.uid).get();
  for (const chat of chats.docs) {
    if (chat.get("type") === "SUPPORT" && (!numbered(chat.get("ticketNumber")) ||
        !chat.get("supportOrigin") || !chat.get("supportTag"))) await repairConversation(chat.id);
  }
  return {ready: true};
});

Object.defineProperty(exports, "repairPlanTicket", {value: repairPlanTicket});
Object.defineProperty(exports, "repairConversation", {value: repairConversation});
