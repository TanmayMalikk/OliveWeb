// Repairs server-owned ticket metadata created before numbered consultation chats.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebaseAdminClient");
const {safetyCaller} = require("./safetyPlanAccess");
const {reserveSupportTicketNumber} = require("./supportTicketNumbers");
const {saveSupportConversation, PLAN_CONSULT} = require("./supportGroups");

const numbered = value => Number.isSafeInteger(value) && value >= 0;
const normalizedNames = value => Object.fromEntries(Object.entries(value || {})
    .map(([uid, name]) => [uid, String(name).trim().toLowerCase()]));

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
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) return;
    const origin = snapshot.get("supportOrigin") || (id.startsWith("plan-consult-") ? PLAN_CONSULT : "SUPPORT");
    const changes = {
      ticketNumber: numbered(snapshot.get("ticketNumber")) ? snapshot.get("ticketNumber") :
        await reserveSupportTicketNumber(transaction),
      supportOrigin: origin,
      supportTag: snapshot.get("adminUid") ? "NEEDS_ADMIN" : origin,
      friendshipId: id,
      participantUsernames: normalizedNames(snapshot.get("participantUsernames")),
      lastMessage: snapshot.get("lastMessage") || "", lastMessageId: snapshot.get("lastMessageId") || "",
    };
    if (Object.entries(changes).some(([key, value]) => JSON.stringify(snapshot.get(key)) !== JSON.stringify(value))) {
      transaction.set(reference, changes, {merge: true});
    }
    return {participantUsernames: changes.participantUsernames, ticketNumber: changes.ticketNumber};
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
