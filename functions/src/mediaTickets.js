// Issues short-lived signed URLs and manages metadata for encrypted media transfers.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const {
  S3Client, PutObjectCommand, CreateMultipartUploadCommand, UploadPartCommand,
  CompleteMultipartUploadCommand, AbortMultipartUploadCommand, GetObjectCommand,
  DeleteObjectCommand, HeadObjectCommand,
} = require("@aws-sdk/client-s3");
const { getSignedUrl } = require("@aws-sdk/s3-request-presigner");
const { db, FieldValue } = require("./firebaseAdminClient");
const crypto = require("node:crypto");

// Secrets are resolved only in the Functions runtime and are never returned to clients.
const accountId = defineSecret("R2_ACCOUNT_ID");
const accessKeyId = defineSecret("R2_ACCESS_KEY_ID");
const secretAccessKey = defineSecret("R2_SECRET_ACCESS_KEY");
const bucketName = defineSecret("R2_BUCKET_NAME");
const secrets = [accountId, accessKeyId, secretAccessKey, bucketName];
// App Check plus Firebase Auth protects every exported media callable.
const options = { secrets, enforceAppCheck: true, invoker: "public" };

// Small ciphertext uploads in one PUT; larger ciphertext uses resumable 8 MiB parts.
const maxBytes = 250 * 1024 * 1024;
const multipartThreshold = 16 * 1024 * 1024;
const partSize = 8 * 1024 * 1024;
const ticketSeconds = 10 * 60;
const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Returns the authenticated UID or rejects the callable before any storage operation.
function caller(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Authentication is required");
  return request.auth.uid;
}

// Builds an S3-compatible client scoped to the configured Cloudflare R2 account.
function client() {
  return new S3Client({
    region: "auto",
    endpoint: `https://${accountId.value()}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId: accessKeyId.value(), secretAccessKey: secretAccessKey.value() },
  });
}

// Validates a non-empty string field within the given maximum length.
function string(value, name, max) {
  if (typeof value !== "string" || value.length === 0 || value.length > max) {
    throw new HttpsError("invalid-argument", `Invalid ${name}`);
  }
  return value;
}

// Validates the 256-bit recovery copy used by authenticated account devices.
function accountAccessKey(value, required = false) {
  if ((value == null || value === "") && !required) return "";
  if (typeof value !== "string" || value.length > 64 ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(value) || Buffer.from(value, "base64").length !== 32) {
    throw new HttpsError("invalid-argument", "Invalid accountAccessKey");
  }
  return value;
}

// Validates the zero-knowledge upload manifest and per-recipient wrapped keys.
function uploadRequest(data, uid) {
  const jobId = string(data?.jobId, "jobId", 64);
  if (!idPattern.test(jobId)) throw new HttpsError("invalid-argument", "Invalid jobId");
  const size = Number(data?.ciphertextSize);
  if (!Number.isSafeInteger(size) || size <= 0 || size > maxBytes) {
    throw new HttpsError("invalid-argument", "Invalid ciphertextSize");
  }
  const checksum = string(data?.ciphertextSha256, "ciphertextSha256", 64).toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(checksum)) {
    throw new HttpsError("invalid-argument", "Invalid ciphertextSha256");
  }
  const encryptedMetadata = string(data?.encryptedMetadata, "encryptedMetadata", 64 * 1024);
  const allowedUsers = Array.isArray(data?.allowedUsers) ? [...new Set(data.allowedUsers)] : [];
  if (
    allowedUsers.length < 1 || allowedUsers.length > 25 || !allowedUsers.includes(uid) ||
    allowedUsers.some((v) => typeof v !== "string" || v.length < 1 || v.length > 128)
  ) {
    throw new HttpsError("invalid-argument", "Invalid allowedUsers");
  }
  const wrappedKeys = data?.wrappedKeys;
  if (
    !wrappedKeys || typeof wrappedKeys !== "object" || Array.isArray(wrappedKeys) ||
    Object.keys(wrappedKeys).length !== allowedUsers.length ||
    allowedUsers.some((id) => typeof wrappedKeys[id] !== "string" || wrappedKeys[id].length < 1 || wrappedKeys[id].length > 2048)
  ) {
    throw new HttpsError("invalid-argument", "Invalid wrappedKeys");
  }
  const encryptionVersion = Number(data?.encryptionVersion);
  if (!Number.isInteger(encryptionVersion) || encryptionVersion < 1 || encryptionVersion > 10) {
    throw new HttpsError("invalid-argument", "Invalid encryptionVersion");
  }
  const deviceWrappedKeys = data?.deviceWrappedKeys == null
    ? {}
    : validateDeviceWrappedKeys(data.deviceWrappedKeys, uid);
  const recoveryKey = accountAccessKey(data?.accountAccessKey);
  return {
    jobId, size, checksum, encryptedMetadata, allowedUsers, wrappedKeys,
    encryptionVersion, deviceWrappedKeys, accountAccessKey: recoveryKey,
  };
}

// Validates wrapped AES keys whose private halves stay on one account's devices.
function validateDeviceWrappedKeys(value, uid) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", "Invalid deviceWrappedKeys");
  }
  const entries = Object.entries(value);
  if (entries.length < 1 || entries.length > 50 || entries.some(([id, wrapped]) =>
    !id.startsWith(`${uid}--`) || id.length > 300 || typeof wrapped !== "string" ||
    wrapped.length < 1 || wrapped.length > 2048)) {
    throw new HttpsError("invalid-argument", "Invalid deviceWrappedKeys");
  }
  return Object.fromEntries(entries);
}

// Validates a complete replacement access list for an already-published encrypted object.
function accessRequest(data, uid) {
  const wrappedKeys = data?.wrappedKeys;
  if (!wrappedKeys || typeof wrappedKeys !== "object" || Array.isArray(wrappedKeys)) {
    throw new HttpsError("invalid-argument", "Invalid wrappedKeys");
  }
  const allowedUsers = Object.keys(wrappedKeys);
  if (
    allowedUsers.length < 1 || allowedUsers.length > 25 || !allowedUsers.includes(uid) ||
    allowedUsers.some((id) => id.length < 1 || id.length > 128 ||
      typeof wrappedKeys[id] !== "string" || wrappedKeys[id].length < 1 || wrappedKeys[id].length > 2048)
  ) {
    throw new HttpsError("invalid-argument", "Invalid wrappedKeys");
  }
  const deviceWrappedKeys = data?.deviceWrappedKeys == null
    ? null
    : validateAccessDeviceWrappedKeys(data.deviceWrappedKeys, allowedUsers);
  return { wrappedKeys, allowedUsers, deviceWrappedKeys };
}

// Validates an exact device-key replacement and rejects wrappers for revoked accounts.
function validateAccessDeviceWrappedKeys(value, allowedUsers) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", "Invalid deviceWrappedKeys");
  }
  const entries = Object.entries(value);
  if (entries.length > 250 || entries.some(([id, wrapped]) => {
    const owner = allowedUsers.find((uid) => id.startsWith(`${uid}--`));
    return !owner || id.length <= owner.length + 2 || id.length > 300 ||
      typeof wrapped !== "string" || wrapped.length < 1 || wrapped.length > 2048;
  })) {
    throw new HttpsError("invalid-argument", "Invalid deviceWrappedKeys");
  }
  return Object.fromEntries(entries);
}

// Evidence updates accept only a boolean so text or numbers cannot change the label.
function evidenceRequest(data) {
  if (typeof data?.evidence !== "boolean") {
    throw new HttpsError("invalid-argument", "evidence must be true or false");
  }
  return data.evidence;
}

// Callable-only resumable session record keyed by the client's stable job id.
function pendingRef(jobId) {
  return db.collection("mediaUploads").doc(jobId);
}

// Published encrypted-media metadata record keyed by the server-generated media id.
function mediaRef(mediaId) {
  return db.collection("files").doc(mediaId);
}

// Callable-only account recovery key record (inaccessible to direct Firestore clients).
function mediaAccessKeyRef(mediaId) {
  return db.collection("mediaAccessKeys").doc(mediaId);
}

// Formats a pending upload session record into the client response payload.
function sessionResponse(value) {
  return {
    jobId: value.jobId, mediaId: value.mediaId, objectKey: value.objectKey,
    mode: value.mode, uploadId: value.uploadId || "", partSize: value.partSize,
  };
}

// Loads a pending session and enforces ownership before tickets, finalization, or abort.
async function ownerPending(jobId, uid) {
  const snapshot = await pendingRef(jobId).get();
  if (!snapshot.exists) throw new HttpsError("not-found", "Upload session was not found");
  const value = snapshot.data();
  if (value.ownerUid !== uid) {
    throw new HttpsError("permission-denied", "Upload session belongs to another user");
  }
  return value;
}

// Maps snake_case Firestore storage fields to the Android repository response contract.
function publicRecord(snapshot) {
  const value = snapshot.data();
  return {
    mediaId: snapshot.id,
    ownerUid: value.owner_id,
    objectKey: value.object_key,
    encryptedMetadata: value.encrypted_metadata,
    wrappedKeys: value.wrapped_keys,
    deviceWrappedKeys: value.device_wrapped_keys || {},
    allowedUsers: value.allowed_users,
    ciphertextSize: value.ciphertext_size,
    ciphertextSha256: value.ciphertext_sha256,
    encryptionVersion: value.encryption_version,
    evidence: value.evidence === true,
    createdAt: value.created_at?.toMillis?.() || Date.now(),
  };
}

// Creates or recovers an idempotent upload session keyed by the client job id.
exports.mediaCreateUpload = onCall(options, async (request) => {
  const uid = caller(request);
  const value = uploadRequest(request.data, uid);
  const reference = pendingRef(value.jobId);
  const existing = await reference.get();
  if (existing.exists) {
    const found = existing.data();
    if (found.ownerUid !== uid || found.ciphertextSha256 !== value.checksum || found.ciphertextSize !== value.size) {
      throw new HttpsError("already-exists", "jobId is already in use");
    }
    if (!found.accountAccessKey && value.accountAccessKey) {
      await reference.update({ accountAccessKey: value.accountAccessKey });
    }
    return sessionResponse(found);
  }
  const mediaId = crypto.randomUUID();
  const objectKey = `users/${uid}/media/${mediaId}`;
  const mode = value.size <= multipartThreshold ? "SINGLE" : "MULTIPART";
  let uploadId = "";
  if (mode === "MULTIPART") {
    const created = await client().send(
      new CreateMultipartUploadCommand({
        Bucket: bucketName.value(), Key: objectKey, ContentType: "application/octet-stream",
      }),
    );
    uploadId = created.UploadId;
    if (!uploadId) throw new HttpsError("internal", "Cloud storage did not create an upload session");
  }
  const pending = {
    jobId: value.jobId, mediaId, objectKey, ownerUid: uid, mode, uploadId,
    partSize: mode === "SINGLE" ? value.size : partSize,
    ciphertextSize: value.size, ciphertextSha256: value.checksum,
    encryptedMetadata: value.encryptedMetadata, wrappedKeys: value.wrappedKeys,
    deviceWrappedKeys: value.deviceWrappedKeys, accountAccessKey: value.accountAccessKey,
    allowedUsers: value.allowedUsers, encryptionVersion: value.encryptionVersion,
    status: "pending", createdAt: FieldValue.serverTimestamp(),
  };
  await reference.create(pending);
  return sessionResponse(pending);
});

// Issues a ten-minute R2 PUT capability URL for one validated part number.
exports.mediaUploadPartTicket = onCall(options, async (request) => {
  const uid = caller(request);
  const jobId = string(request.data?.jobId, "jobId", 64);
  const partNumber = Number(request.data?.partNumber);
  const value = await ownerPending(jobId, uid);
  const total = Math.ceil(value.ciphertextSize / value.partSize);
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > total) {
    throw new HttpsError("invalid-argument", "Invalid partNumber");
  }
  const command = value.mode === "SINGLE"
    ? new PutObjectCommand({
        Bucket: bucketName.value(), Key: value.objectKey, ContentType: "application/octet-stream",
      })
    : new UploadPartCommand({
        Bucket: bucketName.value(), Key: value.objectKey, UploadId: value.uploadId, PartNumber: partNumber,
      });
  const url = await getSignedUrl(client(), command, { expiresIn: ticketSeconds });
  return {
    url,
    headers: value.mode === "SINGLE" ? { "Content-Type": "application/octet-stream" } : {},
    expiresAt: Date.now() + ticketSeconds * 1000,
    partNumber,
  };
});

// Finalizes R2 multipart state, verifies the remote byte count, and publishes Firestore metadata.
exports.mediaCompleteUpload = onCall(options, async (request) => {
  const uid = caller(request);
  const jobId = string(request.data?.jobId, "jobId", 64);
  const value = await ownerPending(jobId, uid);
  const parts = Array.isArray(request.data?.parts) ? request.data.parts : [];
  const completed = await mediaRef(value.mediaId).get();
  if (completed.exists) return publicRecord(completed);
  if (value.mode === "MULTIPART") {
    const count = Math.ceil(value.ciphertextSize / value.partSize);
    if (parts.length !== count) {
      throw new HttpsError("failed-precondition", "Upload parts are incomplete");
    }
    const normalized = parts
      .map((part) => ({ PartNumber: Number(part.partNumber), ETag: string(part.etag, "etag", 256) }))
      .sort((a, b) => a.PartNumber - b.PartNumber);
    if (normalized.some((part, index) => part.PartNumber !== index + 1)) {
      throw new HttpsError("invalid-argument", "Invalid upload parts");
    }
    try {
      await client().send(
        new CompleteMultipartUploadCommand({
          Bucket: bucketName.value(), Key: value.objectKey,
          UploadId: value.uploadId, MultipartUpload: { Parts: normalized },
        }),
      );
    } catch (error) {
      if (error?.name !== "NoSuchUpload") throw error;
    }
  }
  const head = await client().send(
    new HeadObjectCommand({ Bucket: bucketName.value(), Key: value.objectKey }),
  );
  if (Number(head.ContentLength) !== value.ciphertextSize) {
    throw new HttpsError("failed-precondition", "Uploaded size does not match");
  }
  const record = {
    owner_id: uid, object_key: value.objectKey, encrypted_metadata: value.encryptedMetadata,
    wrapped_keys: value.wrappedKeys, device_wrapped_keys: value.deviceWrappedKeys,
    allowed_users: value.allowedUsers, ciphertext_size: value.ciphertextSize,
    ciphertext_sha256: value.ciphertextSha256, encryption_version: value.encryptionVersion,
    evidence: false, created_at: FieldValue.serverTimestamp(),
  };
  const batch = db.batch();
  batch.create(mediaRef(value.mediaId), record).update(pendingRef(jobId), {
    status: "complete", completedAt: FieldValue.serverTimestamp(),
  });
  if (value.accountAccessKey) {
    batch.create(mediaAccessKeyRef(value.mediaId), {
      owner_id: uid, key: value.accountAccessKey, created_at: FieldValue.serverTimestamp(),
    });
  }
  await batch.commit();
  return publicRecord(await mediaRef(value.mediaId).get());
});

// Aborts unfinished multipart resources, then removes the callable-only session record.
exports.mediaAbortUpload = onCall(options, async (request) => {
  const uid = caller(request);
  const jobId = string(request.data?.jobId, "jobId", 64);
  const value = await ownerPending(jobId, uid);
  if (value.mode === "MULTIPART" && value.uploadId) {
    await client().send(
      new AbortMultipartUploadCommand({
        Bucket: bucketName.value(), Key: value.objectKey, UploadId: value.uploadId,
      }),
    );
  }
  await pendingRef(jobId).delete();
  return { aborted: true };
});

// Issues a short-lived direct GET capability only when the caller appears in allowed_users.
exports.mediaDownloadTicket = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const snapshot = await mediaRef(mediaId).get();
  if (!snapshot.exists) throw new HttpsError("not-found", "Media was not found");
  const value = snapshot.data();
  if (!Array.isArray(value.allowed_users) || !value.allowed_users.includes(uid)) {
    throw new HttpsError("permission-denied", "Media access was denied");
  }
  const url = await getSignedUrl(
    client(),
    new GetObjectCommand({ Bucket: bucketName.value(), Key: value.object_key }),
    { expiresIn: ticketSeconds },
  );
  return { url, headers: {}, expiresAt: Date.now() + ticketSeconds * 1000 };
});

// Returns the account recovery copy only after checking the current file access list.
exports.mediaAccountAccessKey = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const [media, recovery] = await Promise.all([
    mediaRef(mediaId).get(), mediaAccessKeyRef(mediaId).get(),
  ]);
  if (!media.exists) throw new HttpsError("not-found", "Media was not found");
  const value = media.data();
  if (!Array.isArray(value.allowed_users) || !value.allowed_users.includes(uid)) {
    throw new HttpsError("permission-denied", "Media access was denied");
  }
  if (!recovery.exists) {
    throw new HttpsError("failed-precondition", "This older document has not been upgraded for account access");
  }
  return { accountAccessKey: accountAccessKey(recovery.data().key, true) };
});

// Lets an owner upgrade a legacy document after decrypting it on an authorized device.
exports.mediaStoreAccountAccessKey = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const key = accountAccessKey(request.data?.accountAccessKey, true);
  const media = await mediaRef(mediaId).get();
  if (!media.exists) throw new HttpsError("not-found", "Media was not found");
  if (media.data().owner_id !== uid) {
    throw new HttpsError("permission-denied", "Only the owner can upgrade document access");
  }
  await mediaAccessKeyRef(mediaId).create({
    owner_id: uid, key, created_at: FieldValue.serverTimestamp(),
  }).catch((error) => {
    if (error?.code !== 6 && error?.code !== "already-exists") throw error;
  });
  return { stored: true };
});

// Owner-only recipient update; ciphertext and encrypted display metadata remain unchanged.
exports.mediaUpdateAccess = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const access = accessRequest(request.data, uid);
  const reference = mediaRef(mediaId);
  const snapshot = await reference.get();
  if (!snapshot.exists) throw new HttpsError("not-found", "Media was not found");
  const value = snapshot.data();
  if (value.owner_id !== uid) {
    throw new HttpsError("permission-denied", "Only the owner can change media access");
  }
  if (typeof value.object_key !== "string" || typeof value.encrypted_metadata !== "string") {
    throw new HttpsError("failed-precondition", "Only encrypted media supports this access update");
  }
  await reference.update({ wrapped_keys: access.wrappedKeys, allowed_users: access.allowedUsers });
  return publicRecord(await reference.get());
});

// Adds key copies for new phones or browsers without changing who may access the document.
exports.mediaUpdateDeviceKeys = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const additions = validateDeviceWrappedKeys(request.data?.deviceWrappedKeys, uid);
  const reference = mediaRef(mediaId);
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(reference);
    if (!snapshot.exists) throw new HttpsError("not-found", "Media was not found");
    const value = snapshot.data();
    if (!Array.isArray(value.allowed_users) || !value.allowed_users.includes(uid)) {
      throw new HttpsError("permission-denied", "Media access was denied");
    }
    const merged = { ...(value.device_wrapped_keys || {}), ...additions };
    if (Object.keys(merged).length > 250) {
      throw new HttpsError("failed-precondition", "Too many approved devices");
    }
    transaction.update(reference, { device_wrapped_keys: merged });
  });
  return publicRecord(await reference.get());
});

// Changes the owner's evidence label without reading or rewriting encrypted file bytes.
exports.mediaUpdateEvidence = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const evidence = evidenceRequest(request.data);
  const reference = mediaRef(mediaId);
  const snapshot = await reference.get();
  if (!snapshot.exists) throw new HttpsError("not-found", "Media was not found");
  if (snapshot.data().owner_id !== uid) {
    throw new HttpsError("permission-denied", "Only the owner can change evidence");
  }
  await reference.update({ evidence });
  return { mediaId, evidence };
});

// Owner-only deletion that removes the R2 object before deleting its Firestore metadata.
exports.mediaDelete = onCall(options, async (request) => {
  const uid = caller(request);
  const mediaId = string(request.data?.mediaId, "mediaId", 64);
  const reference = mediaRef(mediaId);
  const snapshot = await reference.get();
  if (!snapshot.exists) return { deleted: true };
  const value = snapshot.data();
  if (value.owner_id !== uid) {
    throw new HttpsError("permission-denied", "Only the owner can delete media");
  }
  await client().send(
    new DeleteObjectCommand({ Bucket: bucketName.value(), Key: value.object_key }),
  );
  const batch = db.batch();
  batch.delete(reference);
  batch.delete(mediaAccessKeyRef(mediaId));
  await batch.commit();
  return { deleted: true };
});

// Keep validation helpers available to Node tests without exporting another deployed endpoint.
Object.defineProperty(exports, "_test", {
  value: {
    accessRequest, validateDeviceWrappedKeys, validateAccessDeviceWrappedKeys,
    accountAccessKey, evidenceRequest,
  },
  enumerable: false,
});
