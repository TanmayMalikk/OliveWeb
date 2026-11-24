const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { webcrypto } = require("node:crypto");
const opaque = require("@serenity-kit/opaque");

function loadOpaqueBridge() {
  const bundlePath = path.resolve(__dirname, "../app/src/main/assets/opaque/opaque.js");
  const code = fs.readFileSync(bundlePath, "utf8");
  const sandbox = {
    crypto: webcrypto,
    TextEncoder,
    TextDecoder,
    WebAssembly,
    atob,
    btoa,
    console,
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: bundlePath });
  return sandbox.window.opaqueBridge;
}

test("bundled opaqueBridge initializes and completes full registration and login lifecycle", async () => {
  await opaque.ready;
  const bridge = loadOpaqueBridge();
  await bridge.init();
  assert.equal(bridge.isReady, true);

  const serverSetup = opaque.server.createSetup();
  const userIdentifier = "user-bridge-test";
  const password = "correct-horse-battery-staple";

  const regStart = JSON.parse(await bridge.startRegistration(password));
  assert.equal(typeof regStart.clientRegistrationState, "string");
  assert.ok(regStart.clientRegistrationState.length > 0);
  assert.equal(typeof regStart.registrationRequest, "string");
  assert.ok(regStart.registrationRequest.length > 0);

  const serverReg = opaque.server.createRegistrationResponse({
    serverSetup,
    userIdentifier,
    registrationRequest: regStart.registrationRequest,
  });

  const regFinish = JSON.parse(
    await bridge.finishRegistration(
      regStart.clientRegistrationState,
      serverReg.registrationResponse,
      password,
    ),
  );
  assert.equal(typeof regFinish.registrationRecord, "string");
  assert.ok(regFinish.registrationRecord.length > 0);
  assert.equal(typeof regFinish.exportKey, "string");
  assert.equal(Buffer.from(regFinish.exportKey, "base64url").length, 64);

  const loginStart = JSON.parse(await bridge.startLogin(password));
  assert.equal(typeof loginStart.clientLoginState, "string");
  assert.ok(loginStart.clientLoginState.length > 0);
  assert.equal(typeof loginStart.startLoginRequest, "string");
  assert.ok(loginStart.startLoginRequest.length > 0);

  const serverLogin = opaque.server.startLogin({
    serverSetup,
    registrationRecord: regFinish.registrationRecord,
    startLoginRequest: loginStart.startLoginRequest,
    userIdentifier,
  });

  const loginFinish = JSON.parse(
    await bridge.finishLogin(
      loginStart.clientLoginState,
      serverLogin.loginResponse,
      password,
    ),
  );
  assert.equal(typeof loginFinish.finishLoginRequest, "string");
  assert.ok(loginFinish.finishLoginRequest.length > 0);
  assert.equal(loginFinish.exportKey, regFinish.exportKey);

  const wrongStart = JSON.parse(await bridge.startLogin("wrong-password"));
  const serverWrongLogin = opaque.server.startLogin({
    serverSetup,
    registrationRecord: regFinish.registrationRecord,
    startLoginRequest: wrongStart.startLoginRequest,
    userIdentifier,
  });
  const wrongFinish = JSON.parse(
    await bridge.finishLogin(
      wrongStart.clientLoginState,
      serverWrongLogin.loginResponse,
      "wrong-password",
    ),
  );
  assert.deepEqual(wrongFinish, { error: "incorrect_password" });
});
