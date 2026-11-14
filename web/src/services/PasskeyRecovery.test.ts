import { describe, expect, it } from "vitest";
import {
  PasskeyRecoveryError,
  decryptRecoveryKeyWithPrf,
  encryptRecoveryKeyWithPrf,
  getRequiredPrfOutput,
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
});
