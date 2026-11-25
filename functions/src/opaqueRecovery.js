const crypto = require("node:crypto");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const opaque = require("@serenity-kit/opaque");
const { db, FieldValue } = require("./firebaseAdminClient");

const ENVELOPE_VERSION = 1;
const ENVELOPE_ALGORITHM = "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM";
const ENVELOPE_SALT_BYTES = 32;
const ENVELOPE_IV_BYTES = 12;
const ENVELOPE_CIPHERTEXT_BYTES = 48;
const RECENT_AUTHENTICATION_MS = 5 * 60 * 1000;
const SESSION_TTL_MS = 5 * 60 * 1000;
const MAX_UNLOCK_ATTEMPTS = 5;
const THROTTLE_WINDOW_MS = 15 * 60 * 1000;
const THROTTLE_COLLECTION = "accountRecoveryOpaqueThrottle";
const opaqueServerSetup = defineSecret("OPAQUE_SERVER_SETUP");
const callableOptions = { secrets: [opaqueServerSetup], enforceAppCheck: true, invoker: "public" };

/**
 * Returns the stable OPAQUE server setup supplied by Firebase Secret Manager.
 *
 * @param {string} configuredSetup Serialized OPAQUE server setup.
 * @return {Promise<string>} Persistent server setup used for registration and login.
 */
async function getServerSetup(configuredSetup = opaqueServerSetup.value()) {
  await opaque.ready;
  if (typeof configuredSetup !== "string" || configuredSetup.length < 16) {
    throw new HttpsError("failed-precondition", "OPAQUE recovery is not configured.");
  }
  return configuredSetup;
}

function authenticatedUid(request) {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return request.auth.uid;
}

function requireRecentAuthentication(request, now = Date.now()) {
  const authTimeSeconds = request.auth?.token?.auth_time;
  if (
    !Number.isFinite(authTimeSeconds) ||
    now - authTimeSeconds * 1000 > RECENT_AUTHENTICATION_MS ||
    authTimeSeconds * 1000 > now + 60 * 1000
  ) {
    throw new HttpsError("failed-precondition", "Sign in again before updating recovery password.");
  }
}

function base64Bytes(value, expectedSize) {
  if (typeof value !== "string" || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  const bytes = Buffer.from(value, "base64");
  return bytes.length === expectedSize && bytes.toString("base64") === value ? bytes : null;
}

function recordId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{16,1024}$/.test(value) ? value : "";
}

function requiredRecordId(value) {
  const id = recordId(value);
  if (!id) throw new HttpsError("invalid-argument", "Invalid OPAQUE record identifier.");
  return id;
}

function recoveryEnvelope(value, uid, id) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", "Invalid password recovery envelope.");
  }
  const fields = ["version", "accountId", "opaqueRecordId", "algorithm", "salt", "iv", "ciphertext"];
  if (Object.keys(value).length !== fields.length || !fields.every((field) => Object.hasOwn(value, field))) {
    throw new HttpsError("invalid-argument", "Invalid password recovery envelope shape.");
  }
  if (
    value.version !== ENVELOPE_VERSION ||
    value.accountId !== uid ||
    value.opaqueRecordId !== id ||
    value.algorithm !== ENVELOPE_ALGORITHM ||
    !base64Bytes(value.salt, ENVELOPE_SALT_BYTES) ||
    !base64Bytes(value.iv, ENVELOPE_IV_BYTES) ||
    !base64Bytes(value.ciphertext, ENVELOPE_CIPHERTEXT_BYTES)
  ) {
    throw new HttpsError("invalid-argument", "Invalid encrypted recovery key.");
  }
  return { ...value };
}

function opaqueRegistrationRecord(value) {
  if (typeof value !== "string" || value.length < 16 || value.length > 8192) {
    throw new HttpsError("invalid-argument", "Invalid OPAQUE registration record.");
  }
  return value;
}

function requireStoredAccountKeyset(value, uid) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  const base64 = /^[A-Za-z0-9+/]+={0,2}$/;
  if (
    !value ||
    value.version !== 1 ||
    value.accountId !== uid ||
    typeof value.keyId !== "string" ||
    !uuid.test(value.keyId) ||
    value.epoch !== 1 ||
    value.algorithm !== "RSA-OAEP-256" ||
    typeof value.publicKey !== "string" ||
    value.publicKey.length < 400 ||
    value.publicKey.length > 1024 ||
    !base64.test(value.publicKey) ||
    !base64Bytes(value.iv, 12) ||
    typeof value.ciphertext !== "string" ||
    value.ciphertext.length < 2000 ||
    value.ciphertext.length > 4096 ||
    !base64.test(value.ciphertext)
  ) {
    throw new HttpsError("failed-precondition", "Create encrypted account access before adding password recovery.");
  }
  return value;
}

/**
 * Returns whether an account's recovery unlock attempts are currently inside a lockout window.
 *
 * @param {object | null | undefined} throttleData Stored throttle document payload.
 * @param {number} [now=Date.now()] Current epoch milliseconds.
 * @return {boolean} True when max failed attempts were reached and cooldown has not expired.
 */
function isUnlockThrottled(throttleData, now = Date.now()) {
  return Boolean(
    throttleData &&
      typeof throttleData.failedAttempts === "number" &&
      throttleData.failedAttempts >= MAX_UNLOCK_ATTEMPTS &&
      typeof throttleData.lockedUntil === "number" &&
      now < throttleData.lockedUntil
  );
}

/**
 * Computes the next failed-attempt throttle state after an incorrect OPAQUE password verification.
 *
 * @param {object | null | undefined} throttleData Existing throttle document payload.
 * @param {number} [now=Date.now()] Current epoch milliseconds.
 * @return {{failedAttempts: number, lockedUntil: number, updatedAt: number}} Updated throttle state.
 */
function nextThrottleState(throttleData, now = Date.now()) {
  const previousLockExpired =
    typeof throttleData?.lockedUntil === "number" &&
    throttleData.lockedUntil > 0 &&
    now >= throttleData.lockedUntil;
  const previousAttempts =
    !previousLockExpired && Number.isFinite(throttleData?.failedAttempts)
      ? throttleData.failedAttempts
      : 0;
  const failedAttempts = previousAttempts + 1;
  const lockedUntil = failedAttempts >= MAX_UNLOCK_ATTEMPTS ? now + THROTTLE_WINDOW_MS : 0;
  return { failedAttempts, lockedUntil, updatedAt: now };
}

/**
 * 1. opaqueRecoveryBeginRegistration
 * Initiates OPAQUE registration for account recovery.
 */
const opaqueRecoveryBeginRegistration = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  requireRecentAuthentication(request);
  const registrationRequest = request.data?.registrationRequest;
  if (typeof registrationRequest !== "string" || registrationRequest.length < 16 || registrationRequest.length > 4096) {
    throw new HttpsError("invalid-argument", "Invalid OPAQUE registration request.");
  }

  const serverSetup = await getServerSetup();
  const { registrationResponse } = opaque.server.createRegistrationResponse({
    serverSetup,
    userIdentifier: uid,
    registrationRequest,
  });

  return { registrationResponse };
});

/**
 * 2. opaqueRecoveryFinishRegistration
 * Atomically stores the OPAQUE server registration record and the password recovery envelope.
 */
const opaqueRecoveryFinishRegistration = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  requireRecentAuthentication(request);

  const rawRecordId = requiredRecordId(request.data?.opaqueRecordId);
  const registrationRecord = opaqueRegistrationRecord(request.data?.registrationRecord);
  const envelope = recoveryEnvelope(request.data?.envelope, uid, rawRecordId);

  const opaqueDocRef = db.collection("accountRecoveryOpaqueRecords").doc(uid);
  const keysetDocRef = db.collection("accountKeysets").doc(uid);

  await db.runTransaction(async (transaction) => {
    const keysetDoc = await transaction.get(keysetDocRef);
    requireStoredAccountKeyset(keysetDoc.exists ? keysetDoc.data() : null, uid);

    transaction.set(opaqueDocRef, {
      ownerUid: uid,
      recordId: rawRecordId,
      version: 1,
      opaqueRegistrationRecord: registrationRecord,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    transaction.set(
      keysetDocRef,
      {
        passwordRecovery: {
          ...envelope,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        },
      },
      { merge: true }
    );
  });

  return { success: true, opaqueRecordId: rawRecordId };
});

/**
 * 3. opaqueRecoveryBeginUnlock
 * Initiates OPAQUE authentication to unwrap the recovery envelope on a new device.
 */
const opaqueRecoveryBeginUnlock = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const startLoginRequest = request.data?.startLoginRequest;
  if (typeof startLoginRequest !== "string" || startLoginRequest.length < 16 || startLoginRequest.length > 4096) {
    throw new HttpsError("invalid-argument", "Invalid OPAQUE login request.");
  }

  const throttleRef = db.collection(THROTTLE_COLLECTION).doc(uid);
  const [opaqueDoc, throttleDoc] = await Promise.all([
    db.collection("accountRecoveryOpaqueRecords").doc(uid).get(),
    throttleRef.get(),
  ]);
  if (isUnlockThrottled(throttleDoc.exists ? throttleDoc.data() : null)) {
    throw new HttpsError("resource-exhausted", "Too many failed recovery attempts. Try again later.");
  }
  if (!opaqueDoc.exists || opaqueDoc.data()?.ownerUid !== uid) {
    throw new HttpsError("not-found", "No password recovery enrolled for this account.");
  }

  const registrationRecord = opaqueDoc.data().opaqueRegistrationRecord;
  const serverSetup = await getServerSetup();

  const { serverLoginState, loginResponse } = opaque.server.startLogin({
    serverSetup,
    userIdentifier: uid,
    registrationRecord,
    startLoginRequest,
  });

  const sessionId = crypto.randomUUID();
  await db.collection("accountRecoveryOpaqueSessions").doc(sessionId).set({
    ownerUid: uid,
    serverLoginState,
    createdAt: Date.now(),
  });

  return { sessionId, loginResponse };
});

/**
 * 4. opaqueRecoveryFinishUnlock
 * Verifies the client's finish login request and, on success, releases the recovery envelope.
 */
const opaqueRecoveryFinishUnlock = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const sessionId = request.data?.sessionId;
  const finishLoginRequest = request.data?.finishLoginRequest;

  if (typeof sessionId !== "string" || typeof finishLoginRequest !== "string") {
    throw new HttpsError("invalid-argument", "Invalid OPAQUE unlock request.");
  }

  const sessionRef = db.collection("accountRecoveryOpaqueSessions").doc(sessionId);
  const sessionDoc = await sessionRef.get();
  if (!sessionDoc.exists || sessionDoc.data()?.ownerUid !== uid) {
    throw new HttpsError("not-found", "Recovery unlock session expired or invalid.");
  }

  const sessionData = sessionDoc.data();
  // Delete the single-use session immediately
  await sessionRef.delete().catch(() => {});

  if (Date.now() - sessionData.createdAt > SESSION_TTL_MS) {
    throw new HttpsError("deadline-exceeded", "Recovery unlock session expired.");
  }

  const throttleRef = db.collection(THROTTLE_COLLECTION).doc(uid);
  const throttleSnap = await throttleRef.get();
  if (isUnlockThrottled(throttleSnap.exists ? throttleSnap.data() : null)) {
    throw new HttpsError("resource-exhausted", "Too many failed recovery attempts. Try again later.");
  }

  await opaque.ready;
  try {
    opaque.server.finishLogin({
      serverLoginState: sessionData.serverLoginState,
      finishLoginRequest,
    });
  } catch (err) {
    await db.runTransaction(async (transaction) => {
      const current = await transaction.get(throttleRef);
      const next = nextThrottleState(current.exists ? current.data() : null);
      transaction.set(throttleRef, { ownerUid: uid, ...next });
    });
    throw new HttpsError("permission-denied", "Incorrect recovery password.");
  }

  await throttleRef.delete().catch(() => {});

  const keysetDoc = await db.collection("accountKeysets").doc(uid).get();
  const passwordRecovery = keysetDoc.data()?.passwordRecovery;
  if (!passwordRecovery) {
    throw new HttpsError("not-found", "Password recovery envelope not found.");
  }

  return {
    envelope: {
      version: passwordRecovery.version,
      accountId: passwordRecovery.accountId,
      opaqueRecordId: passwordRecovery.opaqueRecordId,
      algorithm: passwordRecovery.algorithm,
      salt: passwordRecovery.salt,
      iv: passwordRecovery.iv,
      ciphertext: passwordRecovery.ciphertext,
    },
  };
});

/**
 * 5. opaqueRecoveryRotate
 * Re-registers new password with OPAQUE and atomically updates recovery envelope.
 */
const opaqueRecoveryRotate = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  requireRecentAuthentication(request);

  const rawRecordId = requiredRecordId(request.data?.opaqueRecordId);
  const registrationRecord = opaqueRegistrationRecord(request.data?.registrationRecord);
  const envelope = recoveryEnvelope(request.data?.envelope, uid, rawRecordId);

  const opaqueDocRef = db.collection("accountRecoveryOpaqueRecords").doc(uid);
  const keysetDocRef = db.collection("accountKeysets").doc(uid);

  await db.runTransaction(async (transaction) => {
    const keysetDoc = await transaction.get(keysetDocRef);
    requireStoredAccountKeyset(keysetDoc.exists ? keysetDoc.data() : null, uid);

    transaction.set(opaqueDocRef, {
      ownerUid: uid,
      recordId: rawRecordId,
      version: 1,
      opaqueRegistrationRecord: registrationRecord,
      updatedAt: FieldValue.serverTimestamp(),
    });

    transaction.set(
      keysetDocRef,
      {
        passwordRecovery: {
          ...envelope,
          updatedAt: FieldValue.serverTimestamp(),
        },
      },
      { merge: true }
    );
  });

  return { success: true, opaqueRecordId: rawRecordId };
});

/**
 * 6. opaqueRecoveryRemove
 * Disallows removal once an OPAQUE recovery password is enabled because it is the sole recovery factor.
 */
const opaqueRecoveryRemove = onCall(callableOptions, async (request) => {
  authenticatedUid(request);
  requireRecentAuthentication(request);
  throw new HttpsError(
    "failed-precondition",
    "Account recovery password cannot be removed once enabled."
  );
});

module.exports = {
  opaqueRecoveryBeginRegistration,
  opaqueRecoveryFinishRegistration,
  opaqueRecoveryBeginUnlock,
  opaqueRecoveryFinishUnlock,
  opaqueRecoveryRotate,
  opaqueRecoveryRemove,
  _test: {
    MAX_UNLOCK_ATTEMPTS,
    THROTTLE_WINDOW_MS,
    recoveryEnvelope,
    opaqueRegistrationRecord,
    requireStoredAccountKeyset,
    isUnlockThrottled,
    nextThrottleState,
    requiredRecordId,
    getServerSetup,
  },
};
