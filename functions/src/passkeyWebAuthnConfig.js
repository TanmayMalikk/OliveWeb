const DEFAULT_RP_ID = "yourolivebranch.ca";
const DEFAULT_ORIGINS = ["https://yourolivebranch.ca"];
const ANDROID_ORIGIN = /^android:apk-key-hash:[A-Za-z0-9_-]{43}$/;

function configuredOrigins(environment) {
  const web = environment.WEBAUTHN_ORIGINS || DEFAULT_ORIGINS.join(",");
  const android = environment.WEBAUTHN_ANDROID_ORIGINS || "";
  return [web, android].join(",")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
}

function webAuthnConfig(environment = process.env) {
  const rpId = environment.WEBAUTHN_RP_ID || DEFAULT_RP_ID;
  const origins = configuredOrigins(environment);
  if (!/^[a-z0-9.-]+$/i.test(rpId) || origins.length === 0) {
    throw new Error("WebAuthn relying-party configuration is invalid.");
  }
  for (const origin of origins) {
    if (ANDROID_ORIGIN.test(origin)) continue;
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
