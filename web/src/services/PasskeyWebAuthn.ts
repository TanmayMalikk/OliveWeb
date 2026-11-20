import {
  startAuthentication,
  startRegistration,
  type AuthenticationResponseJSON,
  type PublicKeyCredentialCreationOptionsJSON,
  type PublicKeyCredentialRequestOptionsJSON,
} from "@simplewebauthn/browser";

// Owns the browser boundary: WebAuthn responses are made server-safe here so
// callers cannot accidentally upload provider-derived PRF key material.
export const browserPasskeys = {
  async register(options: unknown) {
    return {
      response: await startRegistration({
        optionsJSON: registrationOptions(options),
      }),
    };
  },

  async authenticate(options: unknown, prfInputs: Record<string, string>) {
    const response = await startAuthentication({
      optionsJSON: assertionOptions(options, prfInputs),
    });
    return {
      response: withoutPrfResult(response),
      prfOutput: requiredPrfOutput(response),
    };
  },
};

function registrationOptions(value: unknown): PublicKeyCredentialCreationOptionsJSON {
  if (!value || typeof value !== "object" || !nonEmpty((value as { challenge?: unknown }).challenge)) {
    throw new Error("Invalid passkey registration options.");
  }
  return value as PublicKeyCredentialCreationOptionsJSON;
}

function assertionOptions(
  value: unknown,
  prfInputs: Record<string, string>,
): PublicKeyCredentialRequestOptionsJSON {
  if (!value || typeof value !== "object") throw new Error("Invalid passkey assertion options.");
  const options = value as PublicKeyCredentialRequestOptionsJSON;
  if (!nonEmpty(options.challenge) || !nonEmpty(options.rpId)) {
    throw new Error("Invalid passkey assertion options.");
  }
  const evalByCredential = Object.fromEntries(
    Object.entries(prfInputs).map(([credentialId, encoded]) => {
      if (!nonEmpty(credentialId)) throw new Error("Invalid passkey credential.");
      const first = fromBase64(encoded);
      if (first.byteLength !== 32) throw new Error("Invalid passkey PRF input.");
      return [credentialId, { first }];
    }),
  );
  if (Object.keys(evalByCredential).length === 0) {
    throw new Error("No passkey recovery factors are available.");
  }
  return {
    ...options,
    extensions: {
      ...options.extensions,
      prf: { evalByCredential },
    },
  };
}

function requiredPrfOutput(response: AuthenticationResponseJSON): Uint8Array {
  const first = response.clientExtensionResults.prf?.results?.first;
  if (!first) throw new Error("The credential provider did not return a 32-byte PRF result.");
  const bytes = first instanceof ArrayBuffer
    ? new Uint8Array(first)
    : new Uint8Array(first.buffer, first.byteOffset, first.byteLength);
  if (bytes.byteLength !== 32) {
    throw new Error("The credential provider did not return a 32-byte PRF result.");
  }
  return Uint8Array.from(bytes);
}

function withoutPrfResult(response: AuthenticationResponseJSON): AuthenticationResponseJSON {
  const extensions = response.clientExtensionResults;
  const prf = extensions.prf;
  return {
    ...response,
    clientExtensionResults: {
      ...extensions,
      ...(prf ? { prf: { enabled: prf.enabled } } : {}),
    },
  };
}

function fromBase64(value: string): Uint8Array {
  if (!/^[A-Za-z0-9+/_-]+={0,2}$/u.test(value)) throw new Error("Invalid passkey PRF input.");
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  try {
    const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
    if (bytes.byteLength === 0) throw new Error("Invalid passkey PRF input.");
    return bytes;
  } catch {
    throw new Error("Invalid passkey PRF input.");
  }
}

function nonEmpty(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 2048;
}

