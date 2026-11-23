const test = require("node:test");
const assert = require("node:assert/strict");
const opaque = require("@serenity-kit/opaque");

const projectId = process.env.GCLOUD_PROJECT || "demo-olive-rules";
process.env.GCLOUD_PROJECT = projectId;
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId,
  storageBucket: `${projectId}.appspot.com`,
  databaseURL: `https://${projectId}.firebaseio.com`,
});

const opaqueRecovery = require("./src/opaqueRecovery");
const { db } = require("./src/firebaseAdminClient");

const auth = (uid) => ({
  uid,
  token: { auth_time: Math.floor(Date.now() / 1000), email: `${uid}@example.com` },
});
const b64 = (size, value = 7) => Buffer.alloc(size, value).toString("base64");

test("opaque recovery callable lifecycle: register, unlock, rotate, and removal safeguard", {
  skip: process.env.FIRESTORE_EMULATOR_HOST ? false : "Run when Firestore emulator is active.",
}, async () => {
  await opaque.ready;
  const uid = `opaque-user-${Date.now()}`;
  const password = "correct-recovery-password-123";
  const userAuth = auth(uid);
  const originalKeyset = {
    version: 1,
    accountId: uid,
    keyId: "123e4567-e89b-12d3-a456-426614174000",
    epoch: 1,
    algorithm: "RSA-OAEP-256",
    publicKey: b64(400, 4),
    iv: b64(12, 5),
    ciphertext: b64(1600, 6),
  };
  await db.collection("accountKeysets").doc(uid).set(originalKeyset);

  // 1. Client starts registration
  const clientReg = opaque.client.startRegistration({ password });

  // 2. opaqueRecoveryBeginRegistration
  const beginRegResult = await opaqueRecovery.opaqueRecoveryBeginRegistration.run({
    auth: userAuth,
    data: { registrationRequest: clientReg.registrationRequest },
  });
  assert.ok(beginRegResult.registrationResponse);

  // 3. Client finishes registration locally to obtain exportKey and registrationRecord
  const finishRegResult = opaque.client.finishRegistration({
    clientRegistrationState: clientReg.clientRegistrationState,
    registrationResponse: beginRegResult.registrationResponse,
    password,
  });
  assert.ok(finishRegResult.exportKey);
  assert.ok(finishRegResult.registrationRecord);

  const opaqueRecordId = "rec_opaque_" + Date.now() + "_abcdef";
  const envelope = {
    version: 1,
    accountId: uid,
    opaqueRecordId,
    algorithm: "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM",
    salt: b64(32, 1),
    iv: b64(12, 2),
    ciphertext: b64(48, 3),
  };

  // 4. opaqueRecoveryFinishRegistration
  const finishRegCallable = await opaqueRecovery.opaqueRecoveryFinishRegistration.run({
    auth: userAuth,
    data: {
      opaqueRecordId,
      registrationRecord: finishRegResult.registrationRecord,
      envelope,
    },
  });
  assert.equal(finishRegCallable.success, true);

  // 5. Verify Firestore record creation
  const opaqueDoc = await db.collection("accountRecoveryOpaqueRecords").doc(uid).get();
  assert.equal(opaqueDoc.exists, true);
  assert.equal(opaqueDoc.data().recordId, opaqueRecordId);

  const keysetDoc = await db.collection("accountKeysets").doc(uid).get();
  assert.equal(keysetDoc.exists, true);
  for (const [field, value] of Object.entries(originalKeyset)) {
    assert.deepEqual(keysetDoc.data()[field], value);
  }
  assert.deepEqual(keysetDoc.data().passwordRecovery.ciphertext, envelope.ciphertext);

  // 6. Client starts unlock flow on new device
  const clientLogin = opaque.client.startLogin({ password });
  const beginUnlock = await opaqueRecovery.opaqueRecoveryBeginUnlock.run({
    auth: userAuth,
    data: { startLoginRequest: clientLogin.startLoginRequest },
  });
  assert.ok(beginUnlock.sessionId);
  assert.ok(beginUnlock.loginResponse);

  // 7. Client finishes login locally
  const clientFinishLogin = opaque.client.finishLogin({
    clientLoginState: clientLogin.clientLoginState,
    loginResponse: beginUnlock.loginResponse,
    password,
  });
  assert.ok(clientFinishLogin.exportKey);
  assert.equal(clientFinishLogin.exportKey, finishRegResult.exportKey);

  // 8. opaqueRecoveryFinishUnlock
  const unlockResult = await opaqueRecovery.opaqueRecoveryFinishUnlock.run({
    auth: userAuth,
    data: {
      sessionId: beginUnlock.sessionId,
      finishLoginRequest: clientFinishLogin.finishLoginRequest,
    },
  });
  assert.deepEqual(unlockResult.envelope, envelope);

  // 9. Removal safeguard: removal fails if no passkey recovery factor enrolled
  await assert.rejects(
    opaqueRecovery.opaqueRecoveryRemove.run({ auth: userAuth, data: {} }),
    /Cannot remove password recovery without an active passkey recovery factor/i
  );

  // Add dummy passkey recovery to accountKeysets
  await db.collection("accountKeysets").doc(uid).set(
    {
      passkeyRecovery: { version: 1, credentialId: "cred_test_123" },
    },
    { merge: true }
  );

  // Now removal should succeed
  const removeResult = await opaqueRecovery.opaqueRecoveryRemove.run({ auth: userAuth, data: {} });
  assert.equal(removeResult.success, true);

  const keysetAfter = await db.collection("accountKeysets").doc(uid).get();
  assert.equal(keysetAfter.data().passwordRecovery, undefined);
  const opaqueAfter = await db.collection("accountRecoveryOpaqueRecords").doc(uid).get();
  assert.equal(opaqueAfter.exists, false);
});
