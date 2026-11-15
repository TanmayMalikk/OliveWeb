// Emergency reports are created by their owner and readable by authenticated administrators.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {onDocumentWritten, onDocumentCreated} = require("firebase-functions/v2/firestore");
const {db, auth, messaging, FieldValue} = require("./firebaseAdminClient");
const {safetyCaller} = require("./safetyPlanAccess");
const options = {enforceAppCheck: true, invoker: "public"};
const validId = value => typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

exports.emergencyStart = onCall(options, async request => {
  const caller = await safetyCaller(request);
  if (caller.role !== "USER") throw new HttpsError("permission-denied", "User access required.");
  const id = request.data?.id;
  if (!validId(id)) throw new HttpsError("invalid-argument", "Invalid emergency id.");
  const reference = db.collection("emergencyReports").doc(id);
  await db.runTransaction(async tx => {
    const existing = await tx.get(reference);
    if (existing.exists) {
      if (existing.get("ownerUid") !== caller.uid) throw new HttpsError("permission-denied", "Access denied.");
      return;
    }
    tx.create(reference, {
      ownerUid: caller.uid, ownerName: caller.name || caller.uid,
      createdAt: FieldValue.serverTimestamp(), mediaId: "",
      status: "awaiting_report",
    });
  });
  return {id};
});

async function setEmergencyResolution(request, resolved) {
  const caller = await safetyCaller(request);
  if (caller.role !== "ADMIN") throw new HttpsError("permission-denied", "Administrator access required.");
  const id = request.data?.id;
  if (!validId(id)) throw new HttpsError("invalid-argument", "Invalid emergency id.");
  const reference = db.collection("emergencyReports").doc(id);
  await db.runTransaction(async tx => {
    const report = await tx.get(reference);
    if (!report.exists) throw new HttpsError("not-found", "Emergency request not found.");
    if (Boolean(report.get("resolvedAt")) === resolved) return;
    tx.update(reference, resolved
      ? {resolvedAt: FieldValue.serverTimestamp(), resolvedBy: caller.uid}
      : {resolvedAt: null, reopenedAt: FieldValue.serverTimestamp(), reopenedBy: caller.uid});
  });
  return {resolved};
}
exports.emergencyResolve = onCall(options, request => setEmergencyResolution(request, true));
exports.emergencyReopen = onCall(options, request => setEmergencyResolution(request, false));

exports.emergencyAttachUpload = onCall(options, async request => {
  const caller = await safetyCaller(request);
  const {id, jobId} = request.data || {};
  if (!validId(id) || !validId(jobId)) throw new HttpsError("invalid-argument", "Invalid upload.");
  await db.runTransaction(async tx => {
    const reportRef = db.collection("emergencyReports").doc(id);
    const uploadRef = db.collection("emergencyUploads").doc(jobId);
    const [report, existing] = await Promise.all([tx.get(reportRef), tx.get(uploadRef)]);
    if (caller.role !== "USER" || report.get("ownerUid") !== caller.uid) {
      throw new HttpsError("permission-denied", "Access denied.");
    }
    if (existing.exists && (existing.get("emergencyId") !== id || existing.get("ownerUid") !== caller.uid)) {
      throw new HttpsError("already-exists", "Upload already assigned.");
    }
    tx.set(uploadRef, {emergencyId: id, ownerUid: caller.uid});
  });
  await linkReport(jobId);
  return {queued: true};
});

// Both sides of this association retry independently, including an upload finishing before association.
async function linkReport(jobId) {
  await db.runTransaction(async tx => {
    const [link, upload] = await Promise.all([
      tx.get(db.collection("emergencyUploads").doc(jobId)),
      tx.get(db.collection("mediaUploads").doc(jobId)),
    ]);
    if (!link.exists || !upload.exists || upload.get("status") !== "complete") return;
    if (link.get("ownerUid") !== upload.get("ownerUid")) throw new Error("Emergency upload owner mismatch");
    const reportRef = db.collection("emergencyReports").doc(link.get("emergencyId"));
    const fileRef = db.collection("files").doc(upload.get("mediaId"));
    const [report, file] = await Promise.all([tx.get(reportRef), tx.get(fileRef)]);
    if (!report.exists || !file.exists || report.get("ownerUid") !== file.get("owner_id")) return;
    if (report.get("mediaId") && report.get("mediaId") !== file.id) return;
    tx.update(fileRef, {emergency_id: report.id});
    tx.update(reportRef, {mediaId: file.id, status: "ready"});
  });
}

exports.emergencyUploadLinked = onDocumentWritten(
  {document: "emergencyUploads/{jobId}", retry: true}, event => linkReport(event.params.jobId),
);
exports.emergencyUploadCompleted = onDocumentWritten(
  {document: "mediaUploads/{jobId}", retry: true}, event => linkReport(event.params.jobId),
);

exports.notifyEmergency = onDocumentCreated(
  {document: "emergencyReports/{id}", retry: true},
  async event => {
    // Auth records determine recipients, including admins absent from the directory cache.
    let pageToken;
    do {
      const page = await auth.listUsers(1000, pageToken);
      for (const user of page.users) {
        if (user.disabled || user.customClaims?.role !== "ADMIN") continue;
        const devices = await db.collection("notificationTokens").doc(user.uid).collection("devices").get();
        const tokens = devices.docs.map(doc => doc.get("token")).filter(token => typeof token === "string" && token);
        for (let offset = 0; offset < tokens.length; offset += 500) {
          const result = await messaging.sendEachForMulticast({
            tokens: tokens.slice(offset, offset + 500),
            data: {type: "emergency", recipientId: user.uid, emergencyId: event.params.id},
            android: {priority: "high"},
          });
          if (result.responses.some(response => !response.success && ![
            "messaging/registration-token-not-registered", "messaging/invalid-registration-token",
          ].includes(response.error?.code))) throw new Error("Emergency notification delivery needs retry");
        }
      }
      pageToken = page.pageToken;
    } while (pageToken);
  },
);

// Emergency access is independent of conversation or safety-plan grants and uses the existing recovery service.
exports.canReadEmergencyMedia = async (value, uid, mediaId) => {
  if (!value.emergency_id) return false;
  const user = await auth.getUser(uid);
  if (user.disabled || user.customClaims?.role !== "ADMIN") return false;
  const report = await db.collection("emergencyReports").doc(value.emergency_id).get();
  return report.get("ownerUid") === value.owner_id && report.get("mediaId") === mediaId;
};
