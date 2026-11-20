const crypto = require("node:crypto");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} = require("@simplewebauthn/server");
const {db, FieldValue} = require("./firebaseAdminClient");
const {webAuthnConfig} = require("./passkeyWebAuthnConfig");

const CHALLENGE_LIFETIME_MS = 5 * 60 * 1000;
const ENVELOPE_VERSION = 1;
const PRF_INPUT_BYTES = 32;
const ENVELOPE_SALT_BYTES = 32;
const ENVELOPE_IV_BYTES = 12;
const ENVELOPE_CIPHERTEXT_BYTES = 48;
const MAX_CREDENTIAL_BYTES = 4096;
const ALLOWED_TRANSPORTS = new Set(["ble", "hybrid", "internal", "nfc", "usb"]);
const callableOptions = {enforceAppCheck: true, invoker: "public"};

function authenticatedUid(request) {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return request.auth.uid;
}

function base64Bytes(value, expectedSize) {
  if (typeof value !== "string" || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  const bytes = Buffer.from(value, "base64");
  return bytes.length === expectedSize && bytes.toString("base64") === value ? bytes : null;
}

function credentialId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{16,1024}$/.test(value) ? value : "";
}

function requiredCredentialId(value) {
  const id = credentialId(value);
  if (!id) throw new HttpsError("invalid-argument", "Invalid passkey credential.");
  return id;
}

function credentialSummary(value) {
  return {
    credentialId: requiredCredentialId(value?.credentialId),
    deviceType: value?.deviceType === "multiDevice" ? "multiDevice" : "singleDevice",
    backedUp: value?.backedUp === true,
  };
}

function rejectPrfResult(response) {
  if (response?.clientExtensionResults?.prf?.results !== undefined) {
    throw new HttpsError("invalid-argument", "Passkey secret material must remain on the client.");
  }
}

function recoveryEnvelope(value, uid, id) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HttpsError("invalid-argument", "Invalid encrypted recovery key.");
  const fields = ["version", "accountId", "credentialId", "salt", "iv", "ciphertext"];
  if (Object.keys(value).length !== fields.length || !fields.every((field) => Object.hasOwn(value, field))) {
    throw new HttpsError("invalid-argument", "Invalid encrypted recovery key.");
  }
  if (
    value.version !== ENVELOPE_VERSION || value.accountId !== uid || value.credentialId !== id ||
    !base64Bytes(value.salt, ENVELOPE_SALT_BYTES) ||
    !base64Bytes(value.iv, ENVELOPE_IV_BYTES) ||
    !base64Bytes(value.ciphertext, ENVELOPE_CIPHERTEXT_BYTES)
  ) throw new HttpsError("invalid-argument", "Invalid encrypted recovery key.");
  return {...value};
}

function credentialRecord(credential, uid, registrationInfo) {
  const id = credentialId(credential?.id);
  const publicKey = credential?.publicKey;
  const counter = credential?.counter;
  if (!id || !(publicKey instanceof Uint8Array) || publicKey.length < 16 ||
      publicKey.length > MAX_CREDENTIAL_BYTES || !Number.isSafeInteger(counter) || counter < 0) {
    throw new HttpsError("invalid-argument", "Invalid passkey credential.");
  }
  const transports = [...new Set((Array.isArray(credential.transports) ? credential.transports : [])
      .filter((transport) => typeof transport === "string" && ALLOWED_TRANSPORTS.has(transport)))];
  if (transports.length > ALLOWED_TRANSPORTS.size) throw new HttpsError("invalid-argument", "Invalid passkey credential.");
  return {
    ownerUid: uid,
    allowedUsers: [uid],
    credentialId: id,
    publicKey: Buffer.from(publicKey),
    counter,
    transports,
    deviceType: registrationInfo?.credentialDeviceType === "multiDevice" ? "multiDevice" : "singleDevice",
    backedUp: registrationInfo?.credentialBackedUp === true,
  };
}

function storedCredential(snapshot, uid) {
  if (!snapshot.exists) throw new HttpsError("not-found", "Passkey recovery is not configured.");
  const record = snapshot.data() || {};
  const expected = credentialRecord(record, uid, record);
  if (record.ownerUid !== uid || record.credentialId !== snapshot.id) {
    throw new HttpsError("failed-precondition", "Stored passkey recovery record is invalid.");
  }
  return {...expected, envelope: record.envelope, prfInput: record.prfInput};
}

function root(uid) {
  return db.collection("accountRecoveryPasskeys").doc(uid);
}

function challengeReference(uid, challengeId) {
  if (typeof challengeId !== "string" || !/^[A-Za-z0-9_-]{22,128}$/.test(challengeId)) {
    throw new HttpsError("invalid-argument", "Invalid passkey challenge.");
  }
  return root(uid).collection("challenges").doc(challengeId);
}

function validChallenge(snapshot, uid, action) {
  const challenge = snapshot.data();
  const expiresAt = challenge?.expiresAt?.toDate?.() || challenge?.expiresAt;
  if (!snapshot.exists || challenge.ownerUid !== uid || challenge.action !== action ||
      !(expiresAt instanceof Date) || expiresAt.getTime() < Date.now()) {
    throw new HttpsError("failed-precondition", "Passkey challenge expired. Start again.");
  }
  return challenge;
}

async function consumeChallenge(transaction, reference, uid, action) {
  const challenge = validChallenge(await transaction.get(reference), uid, action);
  transaction.delete(reference);
  return challenge;
}

function registrationOptions(uid, credentials) {
  const {rpId} = webAuthnConfig();
  return generateRegistrationOptions({
    rpName: "Olive Branch",
    rpID: rpId,
    userID: new Uint8Array(Buffer.from(uid, "utf8")),
    userName: uid,
    attestationType: "none",
    timeout: CHALLENGE_LIFETIME_MS,
    excludeCredentials: credentials.map((credential) => ({id: credential.credentialId, transports: credential.transports})),
    authenticatorSelection: {residentKey: "required", userVerification: "required"},
    supportedAlgorithmIDs: [-7, -257],
  });
}

async function existingCredentials(uid) {
  const snapshot = await root(uid).collection("credentials").get();
  return snapshot.docs.map((document) => storedCredential(document, uid));
}

exports.passkeyRecoveryListCredentials = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  return {
    credentials: (await existingCredentials(uid)).map(credentialSummary),
  };
});

exports.passkeyRecoveryRemoveCredential = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const id = requiredCredentialId(request.data?.credentialId);
  const reference = root(uid).collection("credentials").doc(id);
  await db.runTransaction(async (transaction) => {
    storedCredential(await transaction.get(reference), uid);
    transaction.delete(reference);
  });
  return {removed: true, credentialId: id};
});

exports.passkeyRecoveryBeginEnrollment = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const options = await registrationOptions(uid, await existingCredentials(uid));
  const challengeId = crypto.randomBytes(24).toString("base64url");
  const prfInput = crypto.randomBytes(PRF_INPUT_BYTES).toString("base64");
  const expiresAt = new Date(Date.now() + CHALLENGE_LIFETIME_MS);
  await challengeReference(uid, challengeId).create({
    ownerUid: uid,
    action: "REGISTER",
    challenge: options.challenge,
    prfInput,
    expiresAt,
    createdAt: FieldValue.serverTimestamp(),
  });
  return {options, challengeId, prfInput, expiresAt: expiresAt.toISOString()};
});

exports.passkeyRecoveryContinueEnrollment = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const reference = challengeReference(uid, request.data?.challengeId);
  const challengeData = validChallenge(await reference.get(), uid, "REGISTER");
  rejectPrfResult(request.data?.response);
  const {rpId, origins} = webAuthnConfig();
  let verification;
  try {
    verification = await verifyRegistrationResponse({
      response: request.data?.response,
      expectedChallenge: challengeData.challenge,
      expectedOrigin: origins,
      expectedRPID: rpId,
      requireUserVerification: true,
      supportedAlgorithmIDs: [-7, -257],
    });
  } catch (error) {
    throw new HttpsError("invalid-argument", "Passkey registration could not be verified.");
  }
  if (!verification.verified || !verification.registrationInfo) {
    throw new HttpsError("permission-denied", "Passkey registration could not be verified.");
  }
  const credential = credentialRecord(verification.registrationInfo.credential, uid, verification.registrationInfo);
  const prfInput = base64Bytes(challengeData.prfInput, PRF_INPUT_BYTES);
  if (!prfInput) throw new HttpsError("failed-precondition", "Passkey challenge is invalid.");
  const options = await generateAuthenticationOptions({
    rpID: rpId,
    timeout: CHALLENGE_LIFETIME_MS,
    userVerification: "required",
    allowCredentials: [{id: credential.credentialId, transports: credential.transports}],
  });
  const proofChallengeId = crypto.randomBytes(24).toString("base64url");
  const proofReference = challengeReference(uid, proofChallengeId);
  const expiresAt = new Date(Date.now() + CHALLENGE_LIFETIME_MS);
  await db.runTransaction(async (transaction) => {
    await consumeChallenge(transaction, reference, uid, "REGISTER");
    const credentialRef = root(uid).collection("credentials").doc(credential.credentialId);
    const prior = await transaction.get(credentialRef);
    if (prior.exists) throw new HttpsError("already-exists", "This passkey is already registered.");
    transaction.create(proofReference, {
      ownerUid: uid,
      action: "ENROLL_PROOF",
      challenge: options.challenge,
      credential,
      prfInput: prfInput.toString("base64"),
      createdAt: FieldValue.serverTimestamp(),
      expiresAt,
    });
  });
  return {
    options,
    challengeId: proofChallengeId,
    prfInputs: {[credential.credentialId]: prfInput.toString("base64")},
    expiresAt: expiresAt.toISOString(),
  };
});

exports.passkeyRecoveryCompleteEnrollment = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  rejectPrfResult(request.data?.response);
  const responseId = credentialId(request.data?.response?.id);
  if (!responseId) throw new HttpsError("invalid-argument", "Invalid passkey response.");
  const reference = challengeReference(uid, request.data?.challengeId);
  const challengeData = validChallenge(await reference.get(), uid, "ENROLL_PROOF");
  const pending = challengeData.credential || {};
  const credential = credentialRecord(pending, uid, {
    credentialDeviceType: pending.deviceType,
    credentialBackedUp: pending.backedUp,
  });
  if (credential.credentialId !== responseId) {
    throw new HttpsError("permission-denied", "Passkey verification failed.");
  }
  const prfInput = base64Bytes(challengeData.prfInput, PRF_INPUT_BYTES);
  if (!prfInput) throw new HttpsError("failed-precondition", "Passkey challenge is invalid.");
  const {rpId, origins} = webAuthnConfig();
  let verification;
  try {
    verification = await verifyAuthenticationResponse({
      response: request.data?.response,
      expectedChallenge: challengeData.challenge,
      expectedOrigin: origins,
      expectedRPID: rpId,
      credential: {
        id: credential.credentialId,
        publicKey: credential.publicKey,
        counter: credential.counter,
        transports: credential.transports,
      },
      requireUserVerification: true,
    });
  } catch (error) {
    throw new HttpsError("invalid-argument", "Passkey verification failed.");
  }
  if (!verification.verified || verification.authenticationInfo.credentialID !== credential.credentialId) {
    throw new HttpsError("permission-denied", "Passkey verification failed.");
  }
  const envelope = recoveryEnvelope(request.data?.envelope, uid, credential.credentialId);
  await db.runTransaction(async (transaction) => {
    await consumeChallenge(transaction, reference, uid, "ENROLL_PROOF");
    const credentialRef = root(uid).collection("credentials").doc(credential.credentialId);
    const prior = await transaction.get(credentialRef);
    if (prior.exists) throw new HttpsError("already-exists", "This passkey is already registered.");
    transaction.create(credentialRef, {
      ...credential,
      counter: verification.authenticationInfo.newCounter,
      backedUp: verification.authenticationInfo.credentialBackedUp,
      envelope,
      prfInput: prfInput.toString("base64"),
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });
  return {registered: true, credentialId: credential.credentialId};
});

exports.passkeyRecoveryBeginUnlock = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  const credentials = await existingCredentials(uid);
  if (credentials.length === 0) throw new HttpsError("not-found", "Passkey recovery is not configured.");
  const {rpId} = webAuthnConfig();
  const options = await generateAuthenticationOptions({
    rpID: rpId,
    timeout: CHALLENGE_LIFETIME_MS,
    userVerification: "required",
    allowCredentials: credentials.map((credential) => ({id: credential.credentialId, transports: credential.transports})),
  });
  const challengeId = crypto.randomBytes(24).toString("base64url");
  const expiresAt = new Date(Date.now() + CHALLENGE_LIFETIME_MS);
  await challengeReference(uid, challengeId).create({
    ownerUid: uid,
    action: "UNLOCK",
    challenge: options.challenge,
    expiresAt,
    createdAt: FieldValue.serverTimestamp(),
  });
  const prfInputs = Object.fromEntries((await root(uid).collection("credentials").get()).docs.map((document) => {
    const data = document.data();
    const input = base64Bytes(data.prfInput, PRF_INPUT_BYTES);
    if (!input) throw new HttpsError("failed-precondition", "Stored passkey recovery record is invalid.");
    return [document.id, input.toString("base64")];
  }));
  return {options, challengeId, prfInputs, expiresAt: expiresAt.toISOString()};
});

exports.passkeyRecoveryCompleteUnlock = onCall(callableOptions, async (request) => {
  const uid = authenticatedUid(request);
  rejectPrfResult(request.data?.response);
  const responseId = credentialId(request.data?.response?.id);
  if (!responseId) throw new HttpsError("invalid-argument", "Invalid passkey response.");
  const reference = challengeReference(uid, request.data?.challengeId);
  const challenge = validChallenge(await reference.get(), uid, "UNLOCK");
  const stored = storedCredential(await root(uid).collection("credentials").doc(responseId).get(), uid);
  const {rpId, origins} = webAuthnConfig();
  let verification;
  try {
    verification = await verifyAuthenticationResponse({
      response: request.data?.response,
      expectedChallenge: challenge.challenge,
      expectedOrigin: origins,
      expectedRPID: rpId,
      credential: {id: stored.credentialId, publicKey: stored.publicKey, counter: stored.counter, transports: stored.transports},
      requireUserVerification: true,
    });
  } catch (error) {
    throw new HttpsError("invalid-argument", "Passkey verification failed.");
  }
  if (!verification.verified || verification.authenticationInfo.credentialID !== stored.credentialId) {
    throw new HttpsError("permission-denied", "Passkey verification failed.");
  }
  let result;
  await db.runTransaction(async (transaction) => {
    await consumeChallenge(transaction, reference, uid, "UNLOCK");
    const credentialRef = root(uid).collection("credentials").doc(stored.credentialId);
    const current = storedCredential(await transaction.get(credentialRef), uid);
    if (current.counter !== stored.counter) throw new HttpsError("aborted", "Passkey state changed. Start again.");
    transaction.update(credentialRef, {
      counter: verification.authenticationInfo.newCounter,
      backedUp: verification.authenticationInfo.credentialBackedUp,
      updatedAt: FieldValue.serverTimestamp(),
    });
    result = {envelope: recoveryEnvelope(current.envelope, uid, current.credentialId), prfInput: current.prfInput};
  });
  return result;
});

exports._test = {
  recoveryEnvelope,
  credentialRecord,
  rejectPrfResult,
  requiredCredentialId,
  credentialSummary,
};
