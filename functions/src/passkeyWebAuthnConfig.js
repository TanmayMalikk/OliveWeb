const DEFAULT_RP_ID = "yourolivebranch.ca";
const DEFAULT_ORIGINS = ["https://yourolivebranch.ca"];

function webAuthnConfig(environment = process.env) {
  const rpId = environment.WEBAUTHN_RP_ID || DEFAULT_RP_ID;
  const origins = (environment.WEBAUTHN_ORIGINS || DEFAULT_ORIGINS.join(","))
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
  if (!/^[a-z0-9.-]+$/i.test(rpId) || origins.length === 0) {
    throw new Error("WebAuthn relying-party configuration is invalid.");
  }
  for (const origin of origins) {
    let parsed;
    try {
      parsed = new URL(origin);
    } catch {
      throw new Error("WebAuthn origin configuration is invalid.");
    }
    if (
      parsed.protocol !== "https:" ||
      parsed.origin !== origin ||
      (parsed.hostname !== rpId && !parsed.hostname.endsWith(`.${rpId}`))
    ) {
      throw new Error("WebAuthn origin configuration is invalid.");
    }
  }
  return {rpId, origins: [...new Set(origins)]};
}

module.exports = {webAuthnConfig};
