import { accountKeysetSession, firebase } from "./AccountService";
import { createOpaqueRecoveryFlow } from "./OpaqueRecoveryFlow";
import {
  unlockAccountKeyset,
  type AccountKeysetEnvelope,
} from "./AccountKeysetCrypto";

/** Restores only an account's locally encrypted keyset into the current browser tab. */
export async function recoverBrowserAccountKeyset(
  uid: string,
  password: string,
): Promise<void> {
  const { db, functions } = await firebase();
  const { doc, getDoc } = await import("firebase/firestore");
  const { httpsCallable } = await import("firebase/functions");
  const snapshot = await getDoc(doc(db, "accountKeysets", uid));
  if (!snapshot.exists()) throw new Error("Encrypted account recovery has not been set up yet.");
  const envelope = snapshot.data() as AccountKeysetEnvelope;
  const flow = createOpaqueRecoveryFlow(
    async (name, data) => (await httpsCallable(functions, name)(data)).data,
  );
  const recoveryKey = await flow.unlock(uid, password);
  try {
    accountKeysetSession.install(await unlockAccountKeyset(uid, recoveryKey, envelope));
  } finally {
    recoveryKey.fill(0);
  }
}
