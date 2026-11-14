export interface RegistrationOptionsJson {
  challenge: string;
  rp: { id?: string; name: string };
  user: { id: string; name: string; displayName: string };
  pubKeyCredParams: PublicKeyCredentialParameters[];
  timeout?: number;
  excludeCredentials?: Array<{
    id: string;
    type: PublicKeyCredentialType;
    transports?: AuthenticatorTransport[];
  }>;
  authenticatorSelection?: AuthenticatorSelectionCriteria;
  attestation?: AttestationConveyancePreference;
}

export interface AssertionOptionsJson {
  challenge: string;
  rpId: string;
  timeout?: number;
  userVerification?: UserVerificationRequirement;
  allowCredentials?: Array<{
    id: string;
    type: PublicKeyCredentialType;
    transports?: AuthenticatorTransport[];
  }>;
}

interface PrfInput {
  first: Uint8Array;
}

interface PrfExtensions {
  prf: {
    eval?: PrfInput;
    evalByCredential?: Record<string, PrfInput>;
  };
}

export function buildRegistrationRequest(
  options: RegistrationOptionsJson,
  prfInput: Uint8Array,
): PublicKeyCredentialCreationOptions {
  assertBytes(prfInput, 32, "Invalid passkey PRF input.");
  return {
    challenge: fromBase64Url(options.challenge),
    rp: options.rp,
    user: {
      ...options.user,
      id: fromBase64Url(options.user.id),
    },
    pubKeyCredParams: options.pubKeyCredParams,
    timeout: options.timeout,
    excludeCredentials: options.excludeCredentials?.map((credential) => ({
      ...credential,
      id: fromBase64Url(credential.id),
    })),
    authenticatorSelection: options.authenticatorSelection,
    attestation: options.attestation,
    extensions: {
      prf: { eval: { first: Uint8Array.from(prfInput) } },
    } as AuthenticationExtensionsClientInputs,
  };
}

export function buildAssertionRequest(
  options: AssertionOptionsJson,
  prfInputs: Record<string, string>,
): PublicKeyCredentialRequestOptions {
  const evalByCredential = Object.fromEntries(Object.entries(prfInputs).map(([credentialId, encoded]) => {
    if (!credentialId) throw new Error("Invalid passkey credential.");
    const input = fromBase64(encoded);
    assertBytes(input, 32, "Invalid passkey PRF input.");
    return [credentialId, {first: Uint8Array.from(input)}];
  }));
  if (Object.keys(evalByCredential).length === 0) throw new Error("No passkey recovery factors are available.");
  return {
    challenge: fromBase64Url(options.challenge),
    rpId: options.rpId,
    timeout: options.timeout,
    userVerification: options.userVerification,
    allowCredentials: options.allowCredentials?.map((credential) => ({
      ...credential,
      id: fromBase64Url(credential.id),
    })),
    extensions: {prf: {evalByCredential}} as AuthenticationExtensionsClientInputs,
  };
}

export function toBase64Url(value: Uint8Array): string {
  return btoa(String.fromCharCode(...value))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/u, "");
}

function fromBase64Url(value: string): ArrayBuffer {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) throw new Error("Invalid WebAuthn binary value.");
  return arrayBuffer(fromBase64(value.replaceAll("-", "+").replaceAll("_", "/")));
}

function fromBase64(value: string): Uint8Array {
  if (!/^[A-Za-z0-9+/_-]+={0,2}$/u.test(value)) throw new Error("Invalid WebAuthn binary value.");
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  try {
    const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
    if (bytes.byteLength === 0) throw new Error("Invalid WebAuthn binary value.");
    return bytes;
  } catch {
    throw new Error("Invalid WebAuthn binary value.");
  }
}

function assertBytes(value: Uint8Array, expected: number, message: string) {
  if (value.byteLength !== expected) throw new Error(message);
}

function arrayBuffer(value: Uint8Array): ArrayBuffer {
  return Uint8Array.from(value).buffer as ArrayBuffer;
}

