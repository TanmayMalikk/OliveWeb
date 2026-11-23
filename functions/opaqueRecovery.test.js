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

test("opaque recovery removal is blocked when no passkey recovery factor exists", () => {
  const accountWithoutPasskey = {
    ownerUid: "uid-a",
    version: 2,
    passwordRecovery: { version: 1 },
    // no passkey recovery
  };
  assert.equal(opaqueRecovery.canRemovePasswordRecovery(accountWithoutPasskey), false);

  const accountWithPasskey = {
    ownerUid: "uid-a",
    version: 2,
    passwordRecovery: { version: 1 },
    passkeyRecovery: { version: 1, credentialId: "cred-1" },
  };
  assert.equal(opaqueRecovery.canRemovePasswordRecovery(accountWithPasskey), true);
});
