import { describe, expect, it, vi } from "vitest";
import * as opaque from "@serenity-kit/opaque";
import {
  createOpaqueRecoveryFlow,
  decryptOpaqueRecoveryEnvelope,
  type OpaqueRecoveryEnvelope,
} from "./OpaqueRecoveryFlow";
import { AccountRecoveryError } from "./AccountKeysetCrypto";

const encoder = new TextEncoder();

function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  return Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
}

async function createAndroidCompatibleEnvelope(
  accountId: string,
  opaqueRecordId: string,
  exportKeyBase64Url: string,
  recoveryKey: Uint8Array,
): Promise<OpaqueRecoveryEnvelope> {
  const exportKeyBytes = fromBase64Url(exportKeyBase64Url);
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const algorithm = "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM" as const;
  const material = await crypto.subtle.importKey(
    "raw",
    exportKeyBytes as BufferSource,
    "HKDF",
    false,
    ["deriveKey"],
  );
  const key = await crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: salt as BufferSource,
      info: encoder.encode(`olive-branch/password-recovery/v1/${accountId}`) as BufferSource,
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  );
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: iv as BufferSource,
      additionalData: encoder.encode(`1|${accountId}|${opaqueRecordId}|${algorithm}`) as BufferSource,
    },
    key,
    recoveryKey as BufferSource,
  );
  exportKeyBytes.fill(0);
  return {
    version: 1,
    accountId,
    opaqueRecordId,
    algorithm,
    salt: toBase64(salt),
    iv: toBase64(iv),
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  };
}

describe("OPAQUE password recovery flow", () => {
  it("completes OPAQUE login exchange and decrypts an Android-compatible ARK envelope", async () => {
    await opaque.ready;
    const accountId = "user_alpha-1";
    const recordId = "rec_1234567890abcdef";
    const password = "CorrectHorseBattery9!";
    const originalArk = crypto.getRandomValues(new Uint8Array(32));

    const serverSetup = opaque.server.createSetup();
    const regStart = opaque.client.startRegistration({ password });
    const regResp = opaque.server.createRegistrationResponse({
      serverSetup,
      userIdentifier: accountId,
      registrationRequest: regStart.registrationRequest,
    });
    const regFinish = opaque.client.finishRegistration({
      clientRegistrationState: regStart.clientRegistrationState,
      registrationResponse: regResp.registrationResponse,
      password,
    });
    const envelope = await createAndroidCompatibleEnvelope(
      accountId,
      recordId,
      regFinish.exportKey,
      originalArk,
    );

    let serverLoginState = "";
    const calls: string[] = [];
    const flow = createOpaqueRecoveryFlow(async (name, data) => {
      calls.push(name);
      const payload = data as Record<string, string>;
      if (name === "opaqueRecoveryBeginUnlock") {
        const started = opaque.server.startLogin({
          serverSetup,
          userIdentifier: accountId,
          registrationRecord: regFinish.registrationRecord,
          startLoginRequest: payload.startLoginRequest,
        });
        serverLoginState = started.serverLoginState;
        return { sessionId: "session_abc_123", loginResponse: started.loginResponse };
      }
      if (name === "opaqueRecoveryFinishUnlock") {
        const finished = opaque.server.finishLogin({
          serverLoginState,
          finishLoginRequest: payload.finishLoginRequest,
        });
        expect(finished.sessionKey).toBeTruthy();
        return { envelope };
      }
      throw new Error(`Unexpected callable ${name}`);
    });

    const recovered = await flow.unlock(accountId, password);
    expect(recovered).toEqual(originalArk);
    expect(calls).toEqual(["opaqueRecoveryBeginUnlock", "opaqueRecoveryFinishUnlock"]);
    recovered.fill(0);
  });

  it("rejects wrong passwords locally before finishUnlock is invoked", async () => {
    await opaque.ready;
    const accountId = "user_alpha-1";
    const password = "CorrectHorseBattery9!";
    const serverSetup = opaque.server.createSetup();
    const regStart = opaque.client.startRegistration({ password });
    const regResp = opaque.server.createRegistrationResponse({
      serverSetup,
      userIdentifier: accountId,
      registrationRequest: regStart.registrationRequest,
    });
    const regFinish = opaque.client.finishRegistration({
      clientRegistrationState: regStart.clientRegistrationState,
      registrationResponse: regResp.registrationResponse,
      password,
    });

    const calls: string[] = [];
    const flow = createOpaqueRecoveryFlow(async (name, data) => {
      calls.push(name);
      const payload = data as Record<string, string>;
      if (name === "opaqueRecoveryBeginUnlock") {
        const started = opaque.server.startLogin({
          serverSetup,
          userIdentifier: accountId,
          registrationRecord: regFinish.registrationRecord,
          startLoginRequest: payload.startLoginRequest,
        });
        return { sessionId: "session_abc_123", loginResponse: started.loginResponse };
      }
      throw new Error(`Unexpected callable ${name}`);
    });

    await expect(flow.unlock(accountId, "WrongPassword99!")).rejects.toThrow(AccountRecoveryError);
    expect(calls).toEqual(["opaqueRecoveryBeginUnlock"]);
  });

  it("rejects mismatched account IDs, record IDs, and tampered ciphertexts", async () => {
    await opaque.ready;
    const exportBytes = crypto.getRandomValues(new Uint8Array(64));
    const exportKeyBase64Url = toBase64(exportBytes)
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
    const envelope = await createAndroidCompatibleEnvelope(
      "user-a",
      "rec_1234567890abcdef",
      exportKeyBase64Url,
      crypto.getRandomValues(new Uint8Array(32)),
    );

    await expect(decryptOpaqueRecoveryEnvelope({
      accountId: "user-b",
      exportKeyBase64Url,
      envelope,
    })).rejects.toThrow(AccountRecoveryError);

    await expect(decryptOpaqueRecoveryEnvelope({
      accountId: "user-a",
      exportKeyBase64Url,
      envelope: { ...envelope, ciphertext: toBase64(crypto.getRandomValues(new Uint8Array(48))) },
    })).rejects.toThrow(AccountRecoveryError);
  });

  it("maps throttled callable errors to a clear cooldown message", async () => {
    const flow = createOpaqueRecoveryFlow(vi.fn().mockRejectedValue(
      Object.assign(new Error("Too many attempts"), { code: "functions/resource-exhausted" }),
    ));

    await expect(flow.unlock("user-a", "SomePassword1!")).rejects.toThrow("15 minutes");
  });
});
