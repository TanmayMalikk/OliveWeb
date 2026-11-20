import {
  decryptRecoveryKeyWithPrf,
  encryptRecoveryKeyWithPrf,
  type PasskeyArkEnvelope,
} from "./PasskeyRecovery";

type Callable = (name: string, data?: unknown) => Promise<unknown>;

interface NativePasskeyClient {
  register(
    options: unknown,
    prfInput: string,
  ): Promise<{ response: { id: string } }>;
  authenticate(
    options: unknown,
    prfInputs: Record<string, string>,
  ): Promise<{ response: { id: string }; prfOutput: Uint8Array }>;
}

interface EnrollmentStart {
  options: unknown;
  challengeId: string;
  prfInput: string;
}

interface UnlockStart {
  options: unknown;
  challengeId: string;
  prfInputs: Record<string, string>;
}

type EnrollmentProof = UnlockStart;

interface EnrollmentComplete {
  registered: true;
  credentialId: string;
}

interface UnlockComplete {
  envelope: PasskeyArkEnvelope;
  prfInput: string;
}

export function createPasskeyRecoveryFlow(
  callable: { call: Callable } & NativePasskeyClient,
) {
  return {
    async enroll(accountId: string, recoveryKey: Uint8Array): Promise<EnrollmentComplete> {
      const start = enrollmentStart(await callable.call("passkeyRecoveryBeginEnrollment"));
      const registration = await callable.register(start.options, start.prfInput);
      const credentialId = credentialIdOf(registration.response);
      const proof = enrollmentProof(await callable.call("passkeyRecoveryContinueEnrollment", {
        challengeId: start.challengeId,
        response: registration.response,
      }));
      const assertion = await callable.authenticate(proof.options, proof.prfInputs);
      const assertionCredentialId = credentialIdOf(assertion.response);
      if (assertionCredentialId !== credentialId || !proof.prfInputs[credentialId]) {
        throw new Error("The passkey proof does not match the registered credential.");
      }
      const prfOutput = new Uint8Array(assertion.prfOutput);
      try {
        const envelope = await encryptRecoveryKeyWithPrf({
          accountId,
          credentialId,
          prfOutput,
          recoveryKey,
        });
        const completed = enrollmentComplete(await callable.call("passkeyRecoveryCompleteEnrollment", {
          challengeId: proof.challengeId,
          response: assertion.response,
          envelope,
        }));
        if (completed.credentialId !== credentialId) {
          throw new Error("The registered passkey does not match the recovery response.");
        }
        return completed;
      } finally {
        prfOutput.fill(0);
      }
    },

    async unlock(accountId: string): Promise<Uint8Array> {
      const start = unlockStart(await callable.call("passkeyRecoveryBeginUnlock"));
      const native = await callable.authenticate(start.options, start.prfInputs);
      const credentialId = credentialIdOf(native.response);
      const prfOutput = new Uint8Array(native.prfOutput);
      try {
        const completed = unlockComplete(await callable.call("passkeyRecoveryCompleteUnlock", {
          challengeId: start.challengeId,
          response: native.response,
        }));
        if (completed.prfInput !== start.prfInputs[credentialId]) {
          throw new Error("The passkey recovery record does not match the requested PRF input.");
        }
        return await decryptRecoveryKeyWithPrf({
          accountId,
          credentialId,
          prfOutput,
          envelope: completed.envelope,
        });
      } finally {
        prfOutput.fill(0);
      }
    },
  };
}

function enrollmentProof(value: unknown): EnrollmentProof {
  try {
    return unlockStart(value);
  } catch {
    throw new Error("Invalid passkey enrollment proof response.");
  }
}

function enrollmentStart(value: unknown): EnrollmentStart {
  if (!value || typeof value !== "object") throw new Error("Invalid passkey enrollment response.");
  const data = value as Partial<EnrollmentStart>;
  if (!data.options || !nonEmpty(data.challengeId) || !nonEmpty(data.prfInput)) {
    throw new Error("Invalid passkey enrollment response.");
  }
  return { options: data.options, challengeId: data.challengeId, prfInput: data.prfInput };
}

function unlockStart(value: unknown): UnlockStart {
  if (!value || typeof value !== "object") throw new Error("Invalid passkey unlock response.");
  const data = value as Partial<UnlockStart>;
  if (!data.options || !nonEmpty(data.challengeId) || !data.prfInputs || typeof data.prfInputs !== "object") {
    throw new Error("Invalid passkey unlock response.");
  }
  for (const [credentialId, input] of Object.entries(data.prfInputs)) {
    if (!nonEmpty(credentialId) || !nonEmpty(input)) throw new Error("Invalid passkey unlock response.");
  }
  return { options: data.options, challengeId: data.challengeId, prfInputs: data.prfInputs };
}

function enrollmentComplete(value: unknown): EnrollmentComplete {
  if (!value || typeof value !== "object") throw new Error("Invalid passkey enrollment completion.");
  const data = value as Partial<EnrollmentComplete>;
  if (data.registered !== true || !nonEmpty(data.credentialId)) {
    throw new Error("Invalid passkey enrollment completion.");
  }
  return { registered: true, credentialId: data.credentialId };
}

function unlockComplete(value: unknown): UnlockComplete {
  if (!value || typeof value !== "object") throw new Error("Invalid passkey unlock completion.");
  const data = value as Partial<UnlockComplete>;
  if (!data.envelope || !nonEmpty(data.prfInput)) throw new Error("Invalid passkey unlock completion.");
  return { envelope: data.envelope, prfInput: data.prfInput };
}

function credentialIdOf(response: { id: string }): string {
  if (!nonEmpty(response.id)) throw new Error("Invalid passkey credential response.");
  return response.id;
}

function nonEmpty(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 1024;
}
