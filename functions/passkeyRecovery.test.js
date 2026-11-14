const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId: "olive-test",
  storageBucket: "olive-test.appspot.com",
  databaseURL: "https://olive-test.firebaseio.com",
});
const { _test: recovery } = require("./src/passkeyRecovery");

const b64 = (size, value = 5) => Buffer.alloc(size, value).toString("base64");

test("passkey recovery accepts only a complete opaque ARK envelope for its owner and credential", () => {
  const envelope = {
    version: 1,
    accountId: "uid-a",
    credentialId: "credential_abcdefghijklmnop",
    salt: b64(32),
    iv: b64(12),
    ciphertext: b64(48),
  };
  assert.deepEqual(recovery.recoveryEnvelope(envelope, "uid-a", envelope.credentialId), envelope);
});

test("passkey recovery rejects altered, raw, oversized, and cross-account recovery data", () => {
  const valid = {
    version: 1,
    accountId: "uid-a",
    credentialId: "credential_abcdefghijklmnop",
    salt: b64(32),
    iv: b64(12),
    ciphertext: b64(48),
  };
  for (const envelope of [
    {...valid, accountId: "uid-b"},
    {...valid, credentialId: "credential_abcdefghijklmnopx"},
    {...valid, recoveryKey: b64(32)},
    {...valid, salt: b64(31)},
    {...valid, iv: b64(13)},
    {...valid, ciphertext: b64(49)},
    {...valid, ciphertext: "not-base64"},
  ]) assert.throws(() => recovery.recoveryEnvelope(envelope, "uid-a", valid.credentialId));
});

test("passkey recovery credential records preserve only public verifier material", () => {
  const record = recovery.credentialRecord({
    id: "credential_abcdefghijklmnop",
    publicKey: Buffer.alloc(77, 9),
    counter: 3,
    transports: ["internal", "usb", "unknown", "usb"],
  }, "uid-a", {credentialDeviceType: "multiDevice", credentialBackedUp: true});

  assert.deepEqual(record, {
    ownerUid: "uid-a",
    allowedUsers: ["uid-a"],
    credentialId: "credential_abcdefghijklmnop",
    publicKey: Buffer.alloc(77, 9),
    counter: 3,
    transports: ["internal", "usb"],
    deviceType: "multiDevice",
    backedUp: true,
  });
});

test("passkey recovery rejects malformed credential data across repeated input classes", () => {
  for (let iteration = 0; iteration < 300; iteration += 1) {
    assert.throws(() => recovery.credentialRecord({
      id: `bad/${iteration}`,
      publicKey: Buffer.alloc(0),
      counter: -1,
      transports: ["nfc", 5, "nfc"],
    }, "uid-a", {}));
  }
});
