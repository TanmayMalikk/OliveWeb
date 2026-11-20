import { describe, expect, it } from "vitest";
import { encryptRecoveryKeyWithPrf } from "./PasskeyRecovery";
import { createPasskeyRecoveryFlow } from "./PasskeyRecoveryFlow";

const accountId = "account-a";
const credentialId = "credential_abcdefghijklmnop";
const prfOutput = new Uint8Array(32).fill(3);
const recoveryKey = new Uint8Array(32).fill(9);

describe("passkey recovery flow", () => {
  it("enrolls an opaque envelope without sending the raw recovery key", async () => {
    const calls: Array<{ name: string; data: unknown }> = [];
    const flow = createPasskeyRecoveryFlow({
      call: async (name, data) => {
        calls.push({ name, data });
        if (name === "passkeyRecoveryBeginEnrollment") {
          return { options: { challenge: "created" }, challengeId: "challenge-a", prfInput: "input-a" };
        }
        if (name === "passkeyRecoveryContinueEnrollment") {
          return {
            options: { challenge: "proof" },
            challengeId: "challenge-proof",
            prfInputs: { [credentialId]: "input-a" },
          };
        }
        return { registered: true, credentialId };
      },
      register: async () => ({ response: { id: credentialId } }),
      authenticate: async () => ({ response: { id: credentialId }, prfOutput }),
    });

    await expect(flow.enroll(accountId, recoveryKey)).resolves.toEqual({
      registered: true,
      credentialId,
    });
    expect(calls.map(({ name }) => name)).toEqual([
      "passkeyRecoveryBeginEnrollment",
      "passkeyRecoveryContinueEnrollment",
      "passkeyRecoveryCompleteEnrollment",
    ]);
    expect(JSON.stringify(calls)).not.toContain(btoa(String.fromCharCode(...recoveryKey)));
    expect(JSON.stringify(calls)).not.toContain("prfOutput");
  });

  it("rejects enrollment when the proof assertion selects another credential", async () => {
    const flow = createPasskeyRecoveryFlow({
      call: async (name) => {
        if (name === "passkeyRecoveryBeginEnrollment") {
          return { options: { challenge: "created" }, challengeId: "challenge-a", prfInput: "input-a" };
        }
        if (name === "passkeyRecoveryContinueEnrollment") {
          return {
            options: { challenge: "proof" },
            challengeId: "challenge-proof",
            prfInputs: { [credentialId]: "input-a" },
          };
        }
        throw new Error("completion must not be called");
      },
      register: async () => ({ response: { id: credentialId } }),
      authenticate: async () => ({ response: { id: "different-credential" }, prfOutput }),
    });

    await expect(flow.enroll(accountId, recoveryKey)).rejects.toThrow("does not match");
  });

  it("returns a recovery key only after an authenticated passkey result", async () => {
    const envelope = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });
    const flow = createPasskeyRecoveryFlow({
      call: async (name) => name === "passkeyRecoveryBeginUnlock"
        ? { options: { challenge: "assertion" }, challengeId: "challenge-b", prfInputs: { [credentialId]: "input-b" } }
        : { envelope, prfInput: "input-b" },
      register: async () => { throw new Error("not used"); },
      authenticate: async () => ({ response: { id: credentialId }, prfOutput }),
    });

    await expect(flow.unlock(accountId)).resolves.toEqual(recoveryKey);
  });

  it("rejects a completion record whose PRF input was not requested for the selected passkey", async () => {
    const envelope = await encryptRecoveryKeyWithPrf({
      accountId,
      credentialId,
      prfOutput,
      recoveryKey,
    });
    const flow = createPasskeyRecoveryFlow({
      call: async (name) => name === "passkeyRecoveryBeginUnlock"
        ? { options: { challenge: "assertion" }, challengeId: "challenge-b", prfInputs: { [credentialId]: "input-b" } }
        : { envelope, prfInput: "different-input" },
      register: async () => { throw new Error("not used"); },
      authenticate: async () => ({ response: { id: credentialId }, prfOutput }),
    });

    await expect(flow.unlock(accountId)).rejects.toThrow("does not match");
  });
});
