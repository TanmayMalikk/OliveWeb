import * as opaque from "@serenity-kit/opaque";
import {
  AccountRecoveryError,
  fromBase64,
  validAccountId,
  validateLength,
} from "./AccountKeysetCrypto";

const encoder = new TextEncoder();
const ENVELOPE_VERSION = 1;
const ENVELOPE_ALGORITHM = "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM";
const HKDF_PREFIX = "olive-branch/password-recovery/v1/";
const SALT_BYTES = 32;
const IV_BYTES = 12;
const RECOVERY_KEY_BYTES = 32;
const RECORD_ID_PATTERN = /^[A-Za-z0-9_-]{16,1024}$/u;

export interface OpaqueRecoveryEnvelope {
  version: 1;
  accountId: string;
  opaqueRecordId: string;
  algorithm: typeof ENVELOPE_ALGORITHM;
  salt: string;
  iv: string;
  ciphertext: string;
}

type Callable = (name: string, data?: unknown) => Promise<unknown>;

function nonEmpty(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function decodeBase64Url(value: string): Uint8Array {
  if (!nonEmpty(value) || !/^[A-Za-z0-9_-]+={0,2}$/u.test(value)) {
    throw new AccountRecoveryError("Invalid OPAQUE export key.");
  }
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  try {
    return Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new AccountRecoveryError("Invalid OPAQUE export key.", error);
  }
}

function parseEnvelope(value: unknown): OpaqueRecoveryEnvelope {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new AccountRecoveryError("Invalid password recovery envelope.");
  }
  const candidate = value as Partial<OpaqueRecoveryEnvelope>;
  if (
    candidate.version !== ENVELOPE_VERSION ||
    !validAccountId(candidate.accountId ?? "") ||
    !RECORD_ID_PATTERN.test(candidate.opaqueRecordId ?? "") ||
    candidate.algorithm !== ENVELOPE_ALGORITHM ||
    !nonEmpty(candidate.salt) ||
    !nonEmpty(candidate.iv) ||
    !nonEmpty(candidate.ciphertext)
  ) {
    throw new AccountRecoveryError("Invalid password recovery envelope.");
  }
  return candidate as OpaqueRecoveryEnvelope;
}

function mapCallableFailure(error: unknown): never {
  if (error instanceof AccountRecoveryError) throw error;
  const code = typeof error === "object" && error !== null && "code" in error
    ? String((error as { code?: unknown }).code)
    : "";
  if (code.includes("resource-exhausted")) {
    throw new AccountRecoveryError(
      "Too many failed recovery attempts. Wait 15 minutes and try again.",
      error,
    );
  }
  if (code.includes("permission-denied")) {
    const msg = error instanceof Error ? error.message : "";
    if (msg.toLowerCase().includes("revoked")) {
      throw new AccountRecoveryError("Recovery is unavailable due to revoked account access.", error);
    }
    throw new AccountRecoveryError("Incorrect recovery password.", error);
  }
  if (code.includes("not-found")) {
    throw new AccountRecoveryError("Password recovery is not configured for this account.", error);
  }
  if (code.includes("unavailable") || code.includes("deadline-exceeded")) {
    throw new AccountRecoveryError("Connection interrupted. Check your network and try again.", error);
  }
  throw error instanceof Error
    ? error
    : new AccountRecoveryError("Password recovery failed.", error);
}

/**
 * Decrypts a server-returned OPAQUE recovery envelope using the client export key
 * and zero-knowledge HKDF-SHA-256 + AES-256-GCM parameters matching Android.
 */
export async function decryptOpaqueRecoveryEnvelope({
  accountId,
  exportKeyBase64Url,
  envelope: rawEnvelope,
}: {
  accountId: string;
  exportKeyBase64Url: string;
  envelope: unknown;
}): Promise<Uint8Array> {
  const envelope = parseEnvelope(rawEnvelope);
  if (envelope.accountId !== accountId) {
    throw new AccountRecoveryError("The password recovery record does not match this account.");
  }
  const salt = fromBase64(envelope.salt);
  const iv = fromBase64(envelope.iv);
  const ciphertext = fromBase64(envelope.ciphertext);
  validateLength(salt, SALT_BYTES, "Invalid recovery salt.");
  validateLength(iv, IV_BYTES, "Invalid recovery IV.");
  if (ciphertext.byteLength <= 16) {
    throw new AccountRecoveryError("Invalid encrypted recovery key.");
  }

  const exportKey = decodeBase64Url(exportKeyBase64Url);
  if (exportKey.byteLength !== 32 && exportKey.byteLength !== 64) {
    exportKey.fill(0);
    throw new AccountRecoveryError("Invalid OPAQUE export key.");
  }

  try {
    const material = await crypto.subtle.importKey(
      "raw",
      exportKey as BufferSource,
      "HKDF",
      false,
      ["deriveKey"],
    );
    const kek = await crypto.subtle.deriveKey(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: salt as BufferSource,
        info: encoder.encode(`${HKDF_PREFIX}${envelope.accountId}`) as BufferSource,
      },
      material,
      { name: "AES-GCM", length: 256 },
      false,
      ["decrypt"],
    );
    const aad = encoder.encode(
      `${envelope.version}|${envelope.accountId}|${envelope.opaqueRecordId}|${envelope.algorithm}`,
    );
    const plaintext = new Uint8Array(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: aad as BufferSource },
      kek,
      ciphertext as BufferSource,
    ));
    validateLength(plaintext, RECOVERY_KEY_BYTES, "Invalid recovery key.");
    return plaintext;
  } catch (error) {
    if (error instanceof AccountRecoveryError) throw error;
    throw new AccountRecoveryError("This password could not unlock your recovery key.", error);
  } finally {
    exportKey.fill(0);
  }
}

/** Coordinates the client-side OPAQUE password unlock flow with Cloud Functions. */
export function createOpaqueRecoveryFlow(call: Callable) {
  return {
    async unlock(accountId: string, password: string): Promise<Uint8Array> {
      if (!validAccountId(accountId)) {
        throw new AccountRecoveryError("Missing account identity.");
      }
      if (!password) {
        throw new AccountRecoveryError("Enter your recovery password.");
      }
      await opaque.ready;
      const loginStart = opaque.client.startLogin({ password });

      let beginRaw: unknown;
      try {
        beginRaw = await call("opaqueRecoveryBeginUnlock", {
          startLoginRequest: loginStart.startLoginRequest,
        });
      } catch (error) {
        mapCallableFailure(error);
      }

      if (!beginRaw || typeof beginRaw !== "object") {
        throw new AccountRecoveryError("Invalid password recovery unlock response.");
      }
      const begin = beginRaw as { sessionId?: unknown; loginResponse?: unknown };
      if (!nonEmpty(begin.sessionId) || !nonEmpty(begin.loginResponse)) {
        throw new AccountRecoveryError("Invalid password recovery unlock response.");
      }

      const loginFinish = opaque.client.finishLogin({
        clientLoginState: loginStart.clientLoginState,
        loginResponse: begin.loginResponse,
        password,
      });
      if (!loginFinish) {
        throw new AccountRecoveryError("Incorrect recovery password.");
      }

      let finishRaw: unknown;
      try {
        finishRaw = await call("opaqueRecoveryFinishUnlock", {
          sessionId: begin.sessionId,
          finishLoginRequest: loginFinish.finishLoginRequest,
        });
      } catch (error) {
        mapCallableFailure(error);
      }

      if (!finishRaw || typeof finishRaw !== "object") {
        throw new AccountRecoveryError("Invalid password recovery unlock completion.");
      }
      const finish = finishRaw as { envelope?: unknown };
      return decryptOpaqueRecoveryEnvelope({
        accountId,
        exportKeyBase64Url: loginFinish.exportKey,
        envelope: finish.envelope,
      });
    },
  };
}
