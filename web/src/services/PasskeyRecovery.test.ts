import { describe, expect, it } from "vitest";
import {
  PasskeyRecoveryError,
  decryptRecoveryKeyWithPrf,
  encryptRecoveryKeyWithPrf,
  getRequiredPrfOutput,
  unlockAccountKeyset,
  type AccountKeysetEnvelope,
} from "./PasskeyRecovery";

const accountId = "account-a";
const credentialId = "credential-a";
const prfOutput = new Uint8Array(32).fill(7);
const recoveryKey = new Uint8Array(32).fill(11);

describe("passkey PRF recovery", () => {
  it("rejects an assertion that does not provide a valid PRF result", () => {
    expect(() => getRequiredPrfOutput({})).toThrow(PasskeyRecoveryError);
    expect(() =>
      getRequiredPrfOutput({ prf: { results: { first: new ArrayBuffer(31) } } }),
    ).toThrow(PasskeyRecoveryError);
  });

  it("encrypts the same recovery key differently on each wrap", async () => {
    const first = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });
    const second = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });

    expect(first.iv).not.toEqual(second.iv);
    expect(first.ciphertext).not.toEqual(second.ciphertext);
  });

  it("opens an account-bound recovery key only with the correct PRF output", async () => {
    const envelope = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });

    await expect(
      decryptRecoveryKeyWithPrf({ accountId, credentialId, prfOutput, envelope }),
    ).resolves.toEqual(recoveryKey);
    await expect(
      decryptRecoveryKeyWithPrf({
        accountId,
        credentialId,
        prfOutput: new Uint8Array(32).fill(8),
        envelope,
      }),
    ).rejects.toThrow(PasskeyRecoveryError);
  });

  it("rejects an account, credential, or ciphertext substitution", async () => {
    const envelope = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });

    await expect(
      decryptRecoveryKeyWithPrf({
        accountId: "account-b",
        credentialId,
        prfOutput,
        envelope,
      }),
    ).rejects.toThrow(PasskeyRecoveryError);
    await expect(
      decryptRecoveryKeyWithPrf({
        accountId,
        credentialId: "credential-b",
        prfOutput,
        envelope,
      }),
    ).rejects.toThrow(PasskeyRecoveryError);
    await expect(
      decryptRecoveryKeyWithPrf({
        accountId,
        credentialId,
        prfOutput,
        envelope: { ...envelope, ciphertext: `${envelope.ciphertext.slice(0, -2)}aa` },
      }),
    ).rejects.toThrow(PasskeyRecoveryError);
  });

  it("does not serialize the recovery key in its remote envelope", async () => {
    const envelope = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });

    expect(JSON.stringify(envelope)).not.toContain(btoa(String.fromCharCode(...recoveryKey)));
  });

  it("opens an Android-format account keyset and WebCrypto OAEP wrapper", async () => {
    const pair = await crypto.subtle.generateKey(
      { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["encrypt", "decrypt"],
    );
    const envelope = await accountKeysetEnvelope(pair, recoveryKey);
    const unlocked = await unlockAccountKeyset(accountId, recoveryKey, envelope);
    const fileKey = new Uint8Array(32).fill(13);
    const wrapped = await crypto.subtle.encrypt({ name: "RSA-OAEP" }, pair.publicKey, fileKey);

    await expect(crypto.subtle.decrypt(
      { name: "RSA-OAEP" },
      unlocked.privateKey,
      wrapped,
    )).resolves.toEqual(fileKey.buffer);
    expect(unlocked.keyId).toBe(envelope.keyId);
  });

  it("rejects account-keyset identity, key, and ciphertext substitution", async () => {
    const pair = await crypto.subtle.generateKey(
      { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["encrypt", "decrypt"],
    );
    const envelope = await accountKeysetEnvelope(pair, recoveryKey);

    await expect(unlockAccountKeyset("account-b", recoveryKey, envelope)).rejects.toThrow(
      PasskeyRecoveryError,
    );
    await expect(unlockAccountKeyset(accountId, new Uint8Array(32).fill(12), envelope)).rejects.toThrow(
      PasskeyRecoveryError,
    );
    await expect(unlockAccountKeyset(accountId, recoveryKey, {
      ...envelope,
      ciphertext: `${envelope.ciphertext.slice(0, -2)}aa`,
    })).rejects.toThrow(PasskeyRecoveryError);
  });
});

async function accountKeysetEnvelope(
  pair: CryptoKeyPair,
  ark: Uint8Array,
): Promise<AccountKeysetEnvelope> {
  const keyId = "123e4567-e89b-12d3-a456-426614174000";
  const iv = new Uint8Array(12).fill(17);
  const privateKey = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const arkBytes = new Uint8Array(ark.byteLength);
  arkBytes.set(ark);
  const aes = await crypto.subtle.importKey("raw", arkBytes, "AES-GCM", false, ["encrypt"]);
  arkBytes.fill(0);
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv,
      additionalData: new TextEncoder().encode(`1|${accountId}|${keyId}|1|RSA-OAEP-256`),
    },
    aes,
    privateKey,
  );
  privateKey.fill(0);
  return {
    version: 1,
    accountId,
    keyId,
    epoch: 1,
    algorithm: "RSA-OAEP-256",
    publicKey: bytesToBase64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey))),
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
  };
}

function bytesToBase64(value: Uint8Array): string {
  return btoa(String.fromCharCode(...value));
}
