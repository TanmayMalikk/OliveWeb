const encoder = new TextEncoder();
const RECOVERY_KEY_BYTES = 32;
const PRF_OUTPUT_BYTES = 32;
const SALT_BYTES = 32;
const IV_BYTES = 12;
const RECOVERY_VERSION = 1;
const HKDF_INFO = encoder.encode("olive.account.ark.passkey-prf.v1");

export class PasskeyRecoveryError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, { cause });
    this.name = "PasskeyRecoveryError";
  }
}

export interface PasskeyArkEnvelope {
  version: 1;
  accountId: string;
  credentialId: string;
  salt: string;
  iv: string;
  ciphertext: string;
}

export interface AccountKeysetEnvelope {
  version: 1;
  accountId: string;
  keyId: string;
  epoch: number;
  algorithm: "RSA-OAEP-256";
  publicKey: string;
  iv: string;
  ciphertext: string;
}

export interface UnlockedAccountKeyset {
  accountId: string;
  keyId: string;
  epoch: number;
  publicKey: CryptoKey;
  privateKey: CryptoKey;
}

export interface PrfClientExtensionResults {
  prf?: {
    results?: {
      first?: ArrayBuffer | Uint8Array;
    };
  };
}

interface EncryptRequest {
  accountId: string;
  credentialId: string;
  prfOutput: Uint8Array;
  recoveryKey: Uint8Array;
  salt?: Uint8Array;
}

interface DecryptRequest {
  accountId: string;
  credentialId: string;
  prfOutput: Uint8Array;
  envelope: PasskeyArkEnvelope;
}

export function getRequiredPrfOutput(
  results: PrfClientExtensionResults,
): Uint8Array {
  const value = results.prf?.results?.first;
  const output = value instanceof ArrayBuffer ? new Uint8Array(value) : value;
  if (!output || output.byteLength !== PRF_OUTPUT_BYTES) {
    throw new PasskeyRecoveryError("This passkey cannot protect your recovery key.");
  }
  return new Uint8Array(output);
}

export async function encryptRecoveryKeyWithPrf({
  accountId,
  credentialId,
  prfOutput,
  recoveryKey,
  salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES)),
}: EncryptRequest): Promise<PasskeyArkEnvelope> {
  validateIdentity(accountId, credentialId);
  validateLength(prfOutput, PRF_OUTPUT_BYTES, "Invalid passkey recovery output.");
  validateLength(recoveryKey, RECOVERY_KEY_BYTES, "Invalid recovery key.");
  validateLength(salt, SALT_BYTES, "Invalid recovery salt.");

  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const key = await deriveKek(prfOutput, salt);
  try {
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: aad(accountId, credentialId) },
      key,
      recoveryKey as BufferSource,
    );
    return {
      version: RECOVERY_VERSION,
      accountId,
      credentialId,
      salt: toBase64(salt),
      iv: toBase64(iv),
      ciphertext: toBase64(new Uint8Array(ciphertext)),
    };
  } catch (error) {
    throw new PasskeyRecoveryError("Could not protect the recovery key.", error);
  }
}

export async function decryptRecoveryKeyWithPrf({
  accountId,
  credentialId,
  prfOutput,
  envelope,
}: DecryptRequest): Promise<Uint8Array> {
  validateIdentity(accountId, credentialId);
  validateLength(prfOutput, PRF_OUTPUT_BYTES, "Invalid passkey recovery output.");
  if (
    envelope.version !== RECOVERY_VERSION ||
    envelope.accountId !== accountId ||
    envelope.credentialId !== credentialId
  ) {
    throw new PasskeyRecoveryError("The passkey recovery record does not match this account.");
  }

  try {
    const salt = fromBase64(envelope.salt);
    const iv = fromBase64(envelope.iv);
    const ciphertext = fromBase64(envelope.ciphertext);
    validateLength(salt, SALT_BYTES, "Invalid recovery salt.");
    validateLength(iv, IV_BYTES, "Invalid recovery IV.");
    if (ciphertext.byteLength <= 16) {
      throw new PasskeyRecoveryError("Invalid encrypted recovery key.");
    }
    const key = await deriveKek(prfOutput, salt);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: aad(accountId, credentialId) },
      key,
      ciphertext as BufferSource,
    );
    const recoveryKey = new Uint8Array(plaintext);
    validateLength(recoveryKey, RECOVERY_KEY_BYTES, "Invalid recovery key.");
    return recoveryKey;
  } catch (error) {
    if (error instanceof PasskeyRecoveryError) throw error;
    throw new PasskeyRecoveryError("This passkey cannot open the recovery key.", error);
  }
}

export async function unlockAccountKeyset(
  accountId: string,
  recoveryKey: Uint8Array,
  envelope: AccountKeysetEnvelope,
): Promise<UnlockedAccountKeyset> {
  validateLength(recoveryKey, RECOVERY_KEY_BYTES, "Invalid recovery key.");
  if (
    envelope.version !== 1 ||
    envelope.accountId !== accountId ||
    !validAccountId(accountId) ||
    !validKeyId(envelope.keyId) ||
    !Number.isSafeInteger(envelope.epoch) ||
    envelope.epoch < 1 ||
    envelope.algorithm !== "RSA-OAEP-256"
  ) {
    throw new PasskeyRecoveryError("The account keyset does not match this account.");
  }
  let plaintext: Uint8Array | undefined;
  try {
    const iv = fromBase64(envelope.iv);
    const ciphertext = fromBase64(envelope.ciphertext);
    const publicKeyBytes = fromBase64(envelope.publicKey);
    validateLength(iv, IV_BYTES, "Invalid account keyset IV.");
    if (ciphertext.byteLength <= 16 || publicKeyBytes.byteLength === 0) {
      throw new PasskeyRecoveryError("Invalid encrypted account keyset.");
    }
    const aes = await crypto.subtle.importKey(
      "raw",
      recoveryKey as BufferSource,
      "AES-GCM",
      false,
      ["decrypt"],
    );
    plaintext = new Uint8Array(await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: iv as BufferSource,
        additionalData: accountKeysetAad(envelope),
      },
      aes,
      ciphertext as BufferSource,
    ));
    const [publicKey, privateKey] = await Promise.all([
      crypto.subtle.importKey(
        "spki",
        publicKeyBytes as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["encrypt"],
      ),
      crypto.subtle.importKey(
        "pkcs8",
        plaintext as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["decrypt"],
      ),
    ]);
    return {
      accountId,
      keyId: envelope.keyId,
      epoch: envelope.epoch,
      publicKey,
      privateKey,
    };
  } catch (error) {
    if (error instanceof PasskeyRecoveryError) throw error;
    throw new PasskeyRecoveryError("This recovery key cannot open the account keyset.", error);
  } finally {
    plaintext?.fill(0);
  }
}

async function deriveKek(prfOutput: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  try {
    const material = await crypto.subtle.importKey(
      "raw",
      prfOutput as BufferSource,
      "HKDF",
      false,
      ["deriveKey"],
    );
    return crypto.subtle.deriveKey(
      { name: "HKDF", hash: "SHA-256", salt: salt as BufferSource, info: HKDF_INFO as BufferSource },
      material,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"],
    );
  } catch (error) {
    throw new PasskeyRecoveryError("Could not derive the passkey recovery key.", error);
  }
}

function validateIdentity(accountId: string, credentialId: string) {
  if (!accountId || !credentialId) {
    throw new PasskeyRecoveryError("Missing account or passkey identity.");
  }
}

function validateLength(value: Uint8Array, expected: number, message: string) {
  if (value.byteLength !== expected) throw new PasskeyRecoveryError(message);
}

function aad(accountId: string, credentialId: string): ArrayBuffer {
  const value = encoder.encode(
    `olive.account.ark.passkey-prf.v${RECOVERY_VERSION}|${accountId}|${credentialId}`,
  );
  return value.buffer as ArrayBuffer;
}

function accountKeysetAad(envelope: AccountKeysetEnvelope): ArrayBuffer {
  return encoder.encode([
    envelope.version,
    envelope.accountId,
    envelope.keyId,
    envelope.epoch,
    envelope.algorithm,
  ].join("|")).buffer as ArrayBuffer;
}

function validAccountId(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,128}$/u.test(value);
}

function validKeyId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(value);
}

function toBase64(value: Uint8Array): string {
  return btoa(String.fromCharCode(...value));
}

function fromBase64(value: string): Uint8Array {
  try {
    return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new PasskeyRecoveryError("Invalid encoded recovery record.", error);
  }
}
