import { accountKeysetSession, firebase } from "./AccountService";
import { browserPasskeys } from "./PasskeyWebAuthn";
import { createPasskeyRecoveryFlow } from "./PasskeyRecoveryFlow";
import {
  unlockAccountKeyset,
  type AccountKeysetEnvelope,
} from "./PasskeyRecovery";

/** Restores only an account's locally encrypted keyset into the current browser tab. */
export async function recoverBrowserAccountKeyset(uid: string): Promise<void> {
  const { db, functions } = await firebase();
  const { doc, getDoc } = await import("firebase/firestore");
  const { httpsCallable } = await import("firebase/functions");
  const snapshot = await getDoc(doc(db, "accountKeysets", uid));
  if (!snapshot.exists()) throw new Error("Encrypted account recovery has not been set up yet.");
  const envelope = snapshot.data() as AccountKeysetEnvelope;
  const flow = createPasskeyRecoveryFlow({
    ...browserPasskeys,
    call: async (name, data) => (await httpsCallable(functions, name)(data)).data,
  });
  const recoveryKey = await flow.unlock(uid);
  try {
    accountKeysetSession.install(await unlockAccountKeyset(uid, recoveryKey, envelope));
  } finally {
    recoveryKey.fill(0);
  }
}
