// Users and staff: opens regular support tickets and sends FCM alerts for conversation updates.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {onDocumentWritten} = require("firebase-functions/v2/firestore");
const {db, messaging} = require("./firebaseAdminClient");
const {listEveryUser, readProfile} = require("./accountStorage");
const {staffEligible, chooseStaff} = require("./safetyPlanPolicy");
const {safetyCaller} = require("./safetyPlanAccess");
const {saveSupportConversation, closeSupportConversation, supportConversationId, SUPPORT} =
  require("./supportGroups");
const {reserveSupportTicketNumber} = require("./supportTicketNumbers");

const STALE_FCM_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

// Determines which conversation participants should receive a discreet FCM update push.
function fcmTargetsForConversationUpdate(before, after, conversationId) {
  const messageId = typeof after?.lastMessageId === "string" ? after.lastMessageId.trim() : "";
  const previousId = typeof before?.lastMessageId === "string" ? before.lastMessageId.trim() : "";
  if (!messageId || messageId === previousId || !Array.isArray(after?.participantIds)) {
    return {recipients: [], data: null};
  }
  const senderUid = typeof after.lastSenderUid === "string" ? after.lastSenderUid.trim() : "";
  const recipients = [...new Set(after.participantIds.filter(
      uid => typeof uid === "string" && uid.trim().length > 0 && uid !== senderUid,
  ))];
  return {
    recipients,
    data: {
      type: "safe_update",
      conversationId: String(conversationId || ""),
      messageId,
      senderUid,
    },
  };
}

// Finds staff accounts that are active and ready to receive support tickets.
async function candidates() {
  const result = [];
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
  const reference = db.collection("supportRequests").doc(
      action === "request" ? newConversationId : (requestedConversationId || caller.uid));
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
  const ticket = await db.runTransaction(async transaction => {
    const references = new Map(staffCandidates.map(staff => [
      staff.uid, db.collection("safetyStaff").doc(staff.uid),
    ]));
    const settings = new Map();
    for (const [uid, staffReference] of references) {
      settings.set(uid, (await transaction.get(staffReference)).data() || {});
    }
    const selected = chooseStaff(staffCandidates.map(staff => ({...staff, ...settings.get(staff.uid)})));
    const ticketNumber = await reserveSupportTicketNumber(transaction);
    if (selected) transaction.set(references.get(selected.uid), {
      activeCount: (selected.activeCount || 0) + 1,
    }, {merge: true});
    const created = {
      ownerUid: caller.uid, ownerName: caller.name, requested: true,
      staffUid: selected?.uid || "", staffName: selected?.name || "",
      adminUid: "", adminName: "", supportOrigin: SUPPORT,
      requestSource: "SUPPORT_BUTTON", sourceConversationId: newConversationId,
      conversationId: newConversationId, ticketNumber, updatedAt: Date.now(),
    };
    transaction.set(reference, created);
    return created;
  });
  if (ticket.staffUid) await saveSupportConversation(ticket, "", false);
  return {ticket};
});

// Sends discreet data-only FCM notifications to conversation recipients when a new message arrives.
exports.notifyConversationUpdate = onDocumentWritten(
    "conversations/{conversationId}",
    async event => {
      const before = event.data?.before?.data();
      const after = event.data?.after?.data();
      const {recipients, data} = fcmTargetsForConversationUpdate(
          before, after, event.params.conversationId,
      );
      if (!data || recipients.length === 0) return;
      await Promise.all(recipients.map(async recipientId => {
        const snapshot = await db.collection("notificationTokens")
            .doc(recipientId)
            .collection("devices")
            .get();
        const docs = snapshot.docs.filter(doc => {
          const token = doc.get("token");
          return typeof token === "string" && token.trim().length > 0;
        });
        if (docs.length === 0) return;
        const tokens = docs.map(doc => doc.get("token").trim());
        const result = await messaging.sendEachForMulticast({
          tokens,
          data: {...data, recipientId},
          android: {priority: "high"},
        });
        const staleDeletes = [];
        result.responses.forEach((response, index) => {
          if (!response.success && STALE_FCM_CODES.has(response.error?.code)) {
            staleDeletes.push(docs[index].ref.delete().catch(() => {}));
          }
        });
        if (staleDeletes.length > 0) await Promise.all(staleDeletes);
      }));
    },
);

Object.defineProperty(exports, "_test", {
  value: {fcmTargetsForConversationUpdate},
  enumerable: false,
});
