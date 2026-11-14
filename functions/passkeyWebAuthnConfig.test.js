const test = require("node:test");
const assert = require("node:assert/strict");
const {webAuthnConfig} = require("./src/passkeyWebAuthnConfig");

test("production recovery is bound to the Olive Branch portal only", () => {
  assert.deepEqual(webAuthnConfig({}), {
    rpId: "yourolivebranch.ca",
    origins: ["https://yourolivebranch.ca"],
  });
});

test("configuration accepts only secure origins under the configured relying-party ID", () => {
  assert.deepEqual(webAuthnConfig({
    WEBAUTHN_RP_ID: "yourolivebranch.ca",
    WEBAUTHN_ORIGINS: "https://yourolivebranch.ca,https://admin.yourolivebranch.ca",
  }), {
    rpId: "yourolivebranch.ca",
    origins: ["https://yourolivebranch.ca", "https://admin.yourolivebranch.ca"],
  });
});

test("configuration rejects paths, insecure origins, and unrelated origins", () => {
  for (const origins of [
    "http://yourolivebranch.ca",
    "https://yourolivebranch.ca/admin",
    "https://example.test",
    "https://notyourolivebranch.ca",
  ]) {
    assert.throws(() => webAuthnConfig({WEBAUTHN_ORIGINS: origins}), /WebAuthn/);
  }
});
