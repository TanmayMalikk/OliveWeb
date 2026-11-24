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
