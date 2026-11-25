const test = require("node:test");
const assert = require("node:assert/strict");

process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId: "olive-test",
  storageBucket: "olive-test.appspot.com",
  databaseURL: "https://olive-test.firebaseio.com",
});

const { _test: opaqueRecovery } = require("./src/opaqueRecovery");

const b64 = (size, value = 5) => Buffer.alloc(size, value).toString("base64");

test("opaque recovery accepts only a valid password-recovery envelope for its owner and record", () => {
  const envelope = {
    version: 1,
    accountId: "uid-a",
    opaqueRecordId: "rec_1234567890123456",
    algorithm: "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM",
    salt: b64(32),
    iv: b64(12),
    ciphertext: b64(48),
  };
  assert.deepEqual(opaqueRecovery.recoveryEnvelope(envelope, "uid-a", envelope.opaqueRecordId), envelope);
});

test("opaque recovery rejects altered, wrong-algorithm, oversized, and cross-account recovery envelopes", () => {
  const valid = {
    version: 1,
    accountId: "uid-a",
    opaqueRecordId: "rec_1234567890123456",
    algorithm: "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM",
    salt: b64(32),
    iv: b64(12),
    ciphertext: b64(48),
  };

  for (const envelope of [
    { ...valid, accountId: "uid-b" },
    { ...valid, opaqueRecordId: "rec_different123456" },
    { ...valid, algorithm: "INSECURE-AES-GCM" },
    { ...valid, version: 2 },
    { ...valid, salt: b64(31) },
    { ...valid, iv: b64(16) },
    { ...valid, ciphertext: b64(47) },
    { ...valid, ciphertext: "not-base64" },
    { ...valid, extraField: "leak" },
  ]) {
    assert.throws(() => opaqueRecovery.recoveryEnvelope(envelope, "uid-a", valid.opaqueRecordId));
  }
});

test("opaque recovery validates opaque registration record string bounds", () => {
  assert.equal(opaqueRecovery.opaqueRegistrationRecord("valid_opaque_record_string_1234567890"), "valid_opaque_record_string_1234567890");
  for (const invalid of [null, undefined, "", 123, {}, "a".repeat(10000)]) {
    assert.throws(() => opaqueRecovery.opaqueRegistrationRecord(invalid));
  }
});

test("opaque recovery requires an existing version-one encrypted account keyset", () => {
  const uid = "uid-a";
  const valid = {
    version: 1,
    accountId: uid,
    keyId: "123e4567-e89b-12d3-a456-426614174000",
    epoch: 1,
    algorithm: "RSA-OAEP-256",
    publicKey: b64(400),
    iv: b64(12),
    ciphertext: b64(1600),
  };
  assert.equal(opaqueRecovery.requireStoredAccountKeyset(valid, uid), valid);

  for (const invalid of [
    null,
    { ...valid, version: 2 },
    { ...valid, accountId: "uid-b" },
    { ...valid, keyId: "not-a-uuid" },
    { ...valid, epoch: 2 },
    { ...valid, algorithm: "RSA-OAEP" },
    { ...valid, publicKey: "not-base64" },
    { ...valid, iv: b64(16) },
    { ...valid, ciphertext: b64(100) },
  ]) {
    assert.throws(() => opaqueRecovery.requireStoredAccountKeyset(invalid, uid));
  }
});

test("opaque recovery rate-limits after 5 failed unlock attempts until cooldown expires", () => {
  const start = 1_700_000_000_000;
  let state = null;
  for (let attempt = 1; attempt < opaqueRecovery.MAX_UNLOCK_ATTEMPTS; attempt++) {
    state = opaqueRecovery.nextThrottleState(state, start + attempt);
    assert.equal(state.failedAttempts, attempt);
    assert.equal(state.lockedUntil, 0);
    assert.equal(opaqueRecovery.isUnlockThrottled(state, start + attempt), false);
  }

  state = opaqueRecovery.nextThrottleState(state, start + 10);
  assert.equal(state.failedAttempts, opaqueRecovery.MAX_UNLOCK_ATTEMPTS);
  assert.equal(state.lockedUntil, start + 10 + opaqueRecovery.THROTTLE_WINDOW_MS);
  assert.equal(opaqueRecovery.isUnlockThrottled(state, start + 11), true);
  assert.equal(
    opaqueRecovery.isUnlockThrottled(state, state.lockedUntil + 1),
    false,
  );

  const afterExpiry = opaqueRecovery.nextThrottleState(state, state.lockedUntil + 1);
  assert.equal(afterExpiry.failedAttempts, 1);
  assert.equal(afterExpiry.lockedUntil, 0);
});

test("opaque recovery fails closed when its persistent server setup is unavailable", async () => {
  await assert.rejects(
    opaqueRecovery.getServerSetup(""),
    /OPAQUE recovery is not configured/i
  );
});

test("opaque recovery callables handle registration, unlock, wrong-password throttle, rotation, and removal", async () => {
  const opaque = require("@serenity-kit/opaque");
  const fixture = require("./test-support/runtime.cjs");
  await opaque.ready;
  const serverSetup = opaque.server.createSetup();
  const f = fixture({ _params: { OPAQUE_SERVER_SETUP: serverSetup } });
  const api = f.load("opaqueRecovery");
  const recentAuth = () => ({ uid: "owner", token: { auth_time: Math.floor(Date.now() / 1000) } });
  const staleAuth = () => ({ uid: "owner", token: { auth_time: Math.floor(Date.now() / 1000) - 600 } });

  await assert.rejects(api.opaqueRecoveryBeginRegistration({ data: {} }), { code: "unauthenticated" });
  await assert.rejects(
    api.opaqueRecoveryBeginRegistration({ auth: staleAuth(), data: { registrationRequest: "x".repeat(32) } }),
    { code: "failed-precondition" }
  );
  await assert.rejects(
    api.opaqueRecoveryBeginRegistration({ auth: recentAuth(), data: { registrationRequest: "short" } }),
    { code: "invalid-argument" }
  );

  // Full registration flow requires an existing stored keyset.
  const regStart = opaque.client.startRegistration({ password: "MySecretPassword123!" });
  const { registrationResponse } = await api.opaqueRecoveryBeginRegistration({
    auth: recentAuth(),
    data: { registrationRequest: regStart.registrationRequest },
  });
  const regFinish = opaque.client.finishRegistration({
    password: "MySecretPassword123!",
    registrationResponse,
    clientRegistrationState: regStart.clientRegistrationState,
  });
  const makeEnvelope = (recordId, byte = 5) => ({
    version: 1,
    accountId: "owner",
    opaqueRecordId: recordId,
    algorithm: "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM",
    salt: b64(32, byte),
    iv: b64(12, byte),
    ciphertext: b64(48, byte),
  });

  const recordId1 = "rec_1234567890123456";
  await assert.rejects(
    api.opaqueRecoveryFinishRegistration({
      auth: recentAuth(),
      data: {
        opaqueRecordId: recordId1,
        registrationRecord: regFinish.registrationRecord,
        envelope: makeEnvelope(recordId1),
      },
    }),
    { code: "failed-precondition" }
  );

  f.records.set("accountKeysets/owner", {
    version: 1,
    accountId: "owner",
    keyId: "123e4567-e89b-42d3-a456-426614174000",
    epoch: 1,
    algorithm: "RSA-OAEP-256",
    publicKey: b64(400),
    iv: b64(12),
    ciphertext: b64(1600),
  });

  assert.deepEqual(
    await api.opaqueRecoveryFinishRegistration({
      auth: recentAuth(),
      data: {
        opaqueRecordId: recordId1,
        registrationRecord: regFinish.registrationRecord,
        envelope: makeEnvelope(recordId1),
      },
    }),
    { success: true, opaqueRecordId: recordId1 }
  );

  // Begin unlock validations & not-found for an unenrolled user.
  await assert.rejects(
    api.opaqueRecoveryBeginUnlock({ auth: { uid: "owner" }, data: { startLoginRequest: "short" } }),
    { code: "invalid-argument" }
  );
  const loginStart = opaque.client.startLogin({ password: "MySecretPassword123!" });
  await assert.rejects(
    api.opaqueRecoveryBeginUnlock({
      auth: { uid: "unenrolled" },
      data: { startLoginRequest: loginStart.startLoginRequest },
    }),
    { code: "not-found" }
  );

  // Wrong password fails finishLogin and increments throttle.
  const wrongStart = opaque.client.startLogin({ password: "WrongPassword!" });
  const wrongBegin = await api.opaqueRecoveryBeginUnlock({
    auth: { uid: "owner" },
    data: { startLoginRequest: wrongStart.startLoginRequest },
  });
  const wrongFinish = opaque.client.finishLogin({
    password: "WrongPassword!",
    loginResponse: wrongBegin.loginResponse,
    clientLoginState: wrongStart.clientLoginState,
  });
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({
      auth: { uid: "owner" },
      data: { sessionId: wrongBegin.sessionId, finishLoginRequest: wrongFinish?.finishLoginRequest ?? "invalid_request_data" },
    }),
    { code: "permission-denied" }
  );
  assert.equal(f.records.get("accountRecoveryOpaqueThrottle/owner").failedAttempts, 1);

  // Finish unlock rejections: bad types, missing/expired session, and throttled account.
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({ auth: { uid: "owner" }, data: { sessionId: 123, finishLoginRequest: 456 } }),
    { code: "invalid-argument" }
  );
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({ auth: { uid: "owner" }, data: { sessionId: "missing", finishLoginRequest: "x" } }),
    { code: "not-found" }
  );
  f.records.set("accountRecoveryOpaqueSessions/expired-sess", {
    ownerUid: "owner",
    serverLoginState: "state",
    createdAt: Date.now() - 10 * 60 * 1000,
  });
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({ auth: { uid: "owner" }, data: { sessionId: "expired-sess", finishLoginRequest: "x" } }),
    { code: "deadline-exceeded" }
  );

  f.records.set("accountRecoveryOpaqueThrottle/owner", {
    failedAttempts: opaqueRecovery.MAX_UNLOCK_ATTEMPTS,
    lockedUntil: Date.now() + 60_000,
  });
  await assert.rejects(
    api.opaqueRecoveryBeginUnlock({ auth: { uid: "owner" }, data: { startLoginRequest: loginStart.startLoginRequest } }),
    { code: "resource-exhausted" }
  );
  f.records.set("accountRecoveryOpaqueSessions/valid-sess", {
    ownerUid: "owner",
    serverLoginState: "state",
    createdAt: Date.now(),
  });
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({ auth: { uid: "owner" }, data: { sessionId: "valid-sess", finishLoginRequest: "x" } }),
    { code: "resource-exhausted" }
  );
  f.records.delete("accountRecoveryOpaqueThrottle/owner");

  // Successful unlock clears throttle and returns the recovery envelope.
  const { sessionId, loginResponse } = await api.opaqueRecoveryBeginUnlock({
    auth: { uid: "owner" },
    data: { startLoginRequest: loginStart.startLoginRequest },
  });
  const loginFinish = opaque.client.finishLogin({
    password: "MySecretPassword123!",
    loginResponse,
    clientLoginState: loginStart.clientLoginState,
  });
  const unlocked = await api.opaqueRecoveryFinishUnlock({
    auth: { uid: "owner" },
    data: { sessionId, finishLoginRequest: loginFinish.finishLoginRequest },
  });
  assert.deepEqual(unlocked.envelope, makeEnvelope(recordId1));

  // Rotate updates registration record and envelope, and remove refuses permanent deletion.
  const rotStart = opaque.client.startRegistration({ password: "NewSecretPassword123!" });
  const rotResp = await api.opaqueRecoveryBeginRegistration({
    auth: recentAuth(),
    data: { registrationRequest: rotStart.registrationRequest },
  });
  const rotFinish = opaque.client.finishRegistration({
    password: "NewSecretPassword123!",
    registrationResponse: rotResp.registrationResponse,
    clientRegistrationState: rotStart.clientRegistrationState,
  });
  const recordId2 = "rec_rotated1234567890";
  assert.deepEqual(
    await api.opaqueRecoveryRotate({
      auth: recentAuth(),
      data: {
        opaqueRecordId: recordId2,
        registrationRecord: rotFinish.registrationRecord,
        envelope: makeEnvelope(recordId2, 9),
      },
    }),
    { success: true, opaqueRecordId: recordId2 }
  );

  // Missing passwordRecovery envelope fails closed.
  const rotLoginStart = opaque.client.startLogin({ password: "NewSecretPassword123!" });
  const rotUnlockBegin = await api.opaqueRecoveryBeginUnlock({
    auth: { uid: "owner" },
    data: { startLoginRequest: rotLoginStart.startLoginRequest },
  });
  const rotUnlockFinish = opaque.client.finishLogin({
    password: "NewSecretPassword123!",
    loginResponse: rotUnlockBegin.loginResponse,
    clientLoginState: rotLoginStart.clientLoginState,
  });
  f.records.set("accountKeysets/owner", {
    ...f.records.get("accountKeysets/owner"),
    passwordRecovery: null,
  });
  await assert.rejects(
    api.opaqueRecoveryFinishUnlock({
      auth: { uid: "owner" },
      data: { sessionId: rotUnlockBegin.sessionId, finishLoginRequest: rotUnlockFinish.finishLoginRequest },
    }),
    { code: "not-found" }
  );

  await assert.rejects(
    api.opaqueRecoveryRemove({ auth: recentAuth(), data: {} }),
    { code: "failed-precondition" }
  );
});
