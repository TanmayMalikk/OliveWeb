// Signed-in accounts: publishes payload-free priority sync revisions and wake-up notifications.
const {onDocumentWritten} = require("firebase-functions/v2/firestore");
const {db, realtime, messaging, FieldValue} = require("./firebaseAdminClient");

const DOMAINS = new Set(["account", "safety", "media", "conversation"]);
const MAX_REVISION = Number.MAX_SAFE_INTEGER - 1;

function strings(values) {
  return [...new Set((Array.isArray(values) ? values : [values])
      .filter((value) => typeof value === "string" && value.length > 0 && value.length <= 128))];
}

function safetyAudience(before = {}, after = {}) {
  return strings([
    before.ownerUid, after.ownerUid,
    before.staffUid, after.staffUid,
    before.adminUid, after.adminUid,
    ...(before.safeContactUids || []),
    ...(after.safeContactUids || []),
  ]);
}

function mediaAudience(before = {}, after = {}) {
  return strings([
    before.owner_id, after.owner_id,
    ...(before.allowed_users || []),
    ...(after.allowed_users || []),
  ]);
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${key}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function conversationAccessChanged(before, after) {
  if (!before || !after) return true;
  return ["participantIds", "participantPublicKeys", "roomKeys", "ownerUid", "staffUid", "adminUid", "closedAt"]
      .some((field) => stable(before[field]) !== stable(after[field]));
}

function conversationAudience(before = {}, after = {}) {
  return strings([...(before.participantIds || []), ...(after.participantIds || [])]);
}

async function adminUids() {
  const snapshot = await db.collection("accountDirectory").where("role", "==", "ADMIN").get();
  return strings(snapshot.docs.map((row) => row.get("uid")));
}

async function writeMarker(uid, domain) {
  if (!DOMAINS.has(domain)) throw new Error("Unknown sync domain");
  const reference = db.collection("syncInvalidations").doc(uid);
  return db.runTransaction(async (transaction) => {
    const current = await transaction.get(reference);
    const data = current.data() || {};
    const currentRevision = (name) =>
      Number.isSafeInteger(data[name]) && data[name] >= 0 ? data[name] : 0;
    const next = (name) => {
      const value = currentRevision(name);
      if (value >= MAX_REVISION) throw new Error("Sync revision exhausted");
      return value + 1;
    };
    const marker = {
      ownerUid: uid,
      allowedUsers: [uid],
      revision: next("revision"),
      accountRevision: domain === "account" ? next("accountRevision") : currentRevision("accountRevision"),
      safetyRevision: domain === "safety" ? next("safetyRevision") : currentRevision("safetyRevision"),
      mediaRevision: domain === "media" ? next("mediaRevision") : currentRevision("mediaRevision"),
      conversationRevision: domain === "conversation" ? next("conversationRevision") : currentRevision("conversationRevision"),
      updatedAt: FieldValue.serverTimestamp(),
    };
    transaction.set(reference, marker);
    return marker.revision;
  });
}

async function deviceTokens(uid) {
  const snapshot = await realtime().ref(`users/${uid}/fcmTokens`).get();
  const values = snapshot.val();
  if (!values || typeof values !== "object") return [];
  return Object.entries(values).filter(([, token]) => typeof token === "string" && token.length > 0)
      .map(([deviceId, token]) => ({deviceId, token}));
}

async function notify(uid, revision) {
  const registrations = await deviceTokens(uid);
  for (let offset = 0; offset < registrations.length; offset += 500) {
    const batch = registrations.slice(offset, offset + 500);
    const result = await messaging.sendEachForMulticast({
      tokens: batch.map(({token}) => token),
      data: {type: "sync_invalidation", revision: String(revision)},
      android: {priority: "high"},
    });
    const removals = {};
    result.responses.forEach((response, index) => {
      const code = response.error?.code;
      if (code === "messaging/registration-token-not-registered" ||
          code === "messaging/invalid-registration-token") {
        removals[`users/${uid}/fcmTokens/${batch[index].deviceId}`] = null;
      }
    });
    if (Object.keys(removals).length > 0) await realtime().ref().update(removals);
  }
}

async function invalidate(uids, domain) {
  await Promise.all(strings(uids).map(async (uid) => {
    const revision = await writeMarker(uid, domain);
    await notify(uid, revision);
  }));
}

exports.syncSafetyPlanInvalidation = onDocumentWritten("safetyPlans/{ownerUid}", async (event) => {
  const before = event.data?.before.exists ? event.data.before.data() : {};
  const after = event.data?.after.exists ? event.data.after.data() : {};
  await invalidate([...safetyAudience(before, after), ...await adminUids()], "safety");
});

exports.syncMediaInvalidation = onDocumentWritten("files/{fileId}", async (event) => {
  const before = event.data?.before.exists ? event.data.before.data() : {};
  const after = event.data?.after.exists ? event.data.after.data() : {};
  await invalidate(mediaAudience(before, after), "media");
});

exports.syncConversationInvalidation = onDocumentWritten("conversations/{conversationId}", async (event) => {
  const before = event.data?.before.exists ? event.data.before.data() : null;
  const after = event.data?.after.exists ? event.data.after.data() : null;
  if (!conversationAccessChanged(before, after)) return;
  await invalidate(conversationAudience(before || {}, after || {}), "conversation");
});

exports.syncConversationDeviceKeyInvalidation = onDocumentWritten(
    "conversations/{conversationId}/deviceKeys/{deviceKeyId}", async (event) => {
      const before = event.data?.before.exists ? event.data.before.data() : {};
      const after = event.data?.after.exists ? event.data.after.data() : {};
      await invalidate(strings([before.uid, after.uid]), "conversation");
    });

exports.syncAccountInvalidation = onDocumentWritten("accountDirectory/{username}", async (event) => {
  const before = event.data?.before.exists ? event.data.before.data() : {};
  const after = event.data?.after.exists ? event.data.after.data() : {};
  await invalidate(strings([before.uid, after.uid]), "account");
});

exports._test = {
  strings,
  safetyAudience,
  mediaAudience,
  conversationAudience,
  conversationAccessChanged,
};

