const test = require("node:test");
const assert = require("node:assert/strict");
const Module = require("node:module");

const projectId = process.env.GCLOUD_PROJECT || "demo-olive-rules";
process.env.GCLOUD_PROJECT = projectId;
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId,
  storageBucket: `${projectId}.appspot.com`,
  databaseURL: `https://${projectId}.firebaseio.com`,
});

const credentialId = "credential_emulator_abcdefghijklmnop";
let assertionCounter = 1;
const webAuthn = require("@simplewebauthn/server");
const originalLoad = Module._load;
Module._load = function(request, parent, isMain) {
  if (request !== "@simplewebauthn/server") return originalLoad.call(this, request, parent, isMain);
  return {
    ...webAuthn,
    verifyRegistrationResponse: async () => ({
      verified: true,
      registrationInfo: {
        credential: {
          id: credentialId,
          publicKey: new Uint8Array(77).fill(9),
          counter: 0,
          transports: ["internal"],
        },
        credentialDeviceType: "multiDevice",
        credentialBackedUp: true,
      },
    }),
    verifyAuthenticationResponse: async () => ({
      verified: true,
      authenticationInfo: {
        credentialID: credentialId,
        newCounter: assertionCounter++,
        credentialBackedUp: true,
      },
    }),
  };
};
const recovery = require("./src/passkeyRecovery");
Module._load = originalLoad;
const {db} = require("./src/firebaseAdminClient");

const auth = (uid) => ({uid, token: {auth_time: Math.floor(Date.now() / 1000)}});
const b64 = (size, value) => Buffer.alloc(size, value).toString("base64");

test("callable enrollment and unlock keep Firestore reads before challenge deletion", {
  skip: process.env.FIRESTORE_EMULATOR_HOST ? false : "Run through test:passkey-functions.",
}, async () => {
  const uid = `passkey-emulator-${Date.now()}`;
  const request = {auth: auth(uid), data: {}};
  const started = await recovery.passkeyRecoveryBeginEnrollment.run(request);
  const registrationResponse = {id: credentialId, clientExtensionResults: {prf: {enabled: true}}};
  const proof = await recovery.passkeyRecoveryContinueEnrollment.run({
    ...request,
    data: {challengeId: started.challengeId, response: registrationResponse},
  });
  const envelope = {
    version: 1,
    accountId: uid,
    credentialId,
    salt: b64(32, 3),
    iv: b64(12, 4),
    ciphertext: b64(48, 5),
  };
  const assertionResponse = {id: credentialId, clientExtensionResults: {prf: {enabled: true}}};
  await recovery.passkeyRecoveryCompleteEnrollment.run({
    ...request,
    data: {challengeId: proof.challengeId, response: assertionResponse, envelope},
  });

  const unlock = await recovery.passkeyRecoveryBeginUnlock.run(request);
  const completed = await recovery.passkeyRecoveryCompleteUnlock.run({
    ...request,
    data: {challengeId: unlock.challengeId, response: assertionResponse},
  });

  assert.deepEqual(completed.envelope, envelope);
  assert.equal(completed.prfInput, unlock.prfInputs[credentialId]);
  const root = db.collection("accountRecoveryPasskeys").doc(uid);
  const [credential, challenges] = await Promise.all([
    root.collection("credentials").doc(credentialId).get(),
    root.collection("challenges").get(),
  ]);
  assert.equal(credential.get("counter"), 2);
  assert.equal(challenges.empty, true);
  await assert.rejects(
    recovery.passkeyRecoveryCompleteUnlock.run({
      ...request,
      data: {challengeId: unlock.challengeId, response: assertionResponse},
    }),
    /expired/i,
  );
});
