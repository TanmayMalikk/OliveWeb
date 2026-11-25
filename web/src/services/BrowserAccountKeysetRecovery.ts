import { accountKeysetSession, firebase } from "./AccountService";
import { createOpaqueRecoveryFlow } from "./OpaqueRecoveryFlow";
import {
  createAccountKeysetEnvelope,
  unlockAccountKeyset,
  type AccountKeysetEnvelope,
} from "./AccountKeysetCrypto";

export interface BrowserPasswordRecoveryStatus {
  hasKeyset: boolean;
  enrolled: boolean;
}

async function reauthenticateForRecovery(uid: string, password: string): Promise<void> {
  if (!password) throw new Error("Enter your account password.");
  const { auth } = await firebase();
  const user = auth.currentUser;
  if (!user || user.uid !== uid) {
    throw new Error("Sign in again before managing account recovery.");
  }
  if (!user.email) {
    throw new Error("This account has no sign-in email.");
  }
  const { EmailAuthProvider, reauthenticateWithCredential } = await import("firebase/auth");
  await reauthenticateWithCredential(user, EmailAuthProvider.credential(user.email, password));
  await user.getIdToken(true);
}

/** Checks whether the account has an encrypted keyset and OPAQUE password recovery enrolled. */
export async function getBrowserPasswordRecoveryStatus(
  uid: string,
): Promise<BrowserPasswordRecoveryStatus> {
  const { db } = await firebase();
  const { doc, getDoc } = await import("firebase/firestore");
  const snapshot = await getDoc(doc(db, "accountKeysets", uid));
  if (!snapshot.exists()) return { hasKeyset: false, enrolled: false };
  const data = snapshot.data() as { passwordRecovery?: unknown } | undefined;
  return {
    hasKeyset: true,
    enrolled: Boolean(data?.passwordRecovery && typeof data.passwordRecovery === "object"),
  };
}

/** Creates an account recovery keyset if absent, enrolls OPAQUE recovery, and unlocks this tab. */
export async function enrollBrowserAccountRecovery(
  uid: string,
  password: string,
): Promise<void> {
  if (!password) throw new Error("Enter your account password.");
  await reauthenticateForRecovery(uid, password);
  const { db, functions } = await firebase();
  const { doc, getDoc, serverTimestamp, writeBatch } = await import("firebase/firestore");
  const { httpsCallable } = await import("firebase/functions");
  const snapshot = await getDoc(doc(db, "accountKeysets", uid));
  if (snapshot.exists()) {
    const existing = snapshot.data() as { passwordRecovery?: unknown } | undefined;
    if (existing?.passwordRecovery && typeof existing.passwordRecovery === "object") {
      throw new Error("Password recovery is already enabled for this account. Unlock it using your recovery password.");
    }
    throw new Error("An encrypted keyset already exists on another device. Enable password recovery from that device so existing data stays accessible.");
  }

  const recoveryKey = crypto.getRandomValues(new Uint8Array(32));
  try {
    const { envelope, keyset } = await createAccountKeysetEnvelope(uid, recoveryKey);
    const batch = writeBatch(db);
    batch.set(
      doc(db, "accountKeysets", uid),
      { ...envelope, updatedAt: serverTimestamp() },
      { merge: true },
    );
    batch.set(doc(db, "accountKeyDirectories", uid), {
      ownerUid: uid,
      keyId: envelope.keyId,
      epoch: envelope.epoch,
      algorithm: envelope.algorithm,
      publicKey: envelope.publicKey,
      updatedAt: serverTimestamp(),
    });
    await batch.commit();

    const flow = createOpaqueRecoveryFlow(
      async (name, data) => (await httpsCallable(functions, name)(data)).data,
    );
    await flow.register(uid, password, recoveryKey);
    accountKeysetSession.install(keyset);
  } finally {
    recoveryKey.fill(0);
  }
}

/** Rotates an enrolled account's OPAQUE password recovery envelope after a password change. */
export async function rotateBrowserPasswordRecovery(
  uid: string,
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const { db, functions } = await firebase();
  const { doc, getDoc } = await import("firebase/firestore");
  const { httpsCallable } = await import("firebase/functions");
  const snapshot = await getDoc(doc(db, "accountKeysets", uid));
  if (!snapshot.exists()) return;
  const data = snapshot.data() as AccountKeysetEnvelope & { passwordRecovery?: unknown };
  if (!data.passwordRecovery || typeof data.passwordRecovery !== "object") return;

  await reauthenticateForRecovery(uid, newPassword);
  const flow = createOpaqueRecoveryFlow(
    async (name, payload) => (await httpsCallable(functions, name)(payload)).data,
  );
  const recoveryKey = await flow.unlock(uid, currentPassword);
  try {
    await flow.rotate(uid, newPassword, recoveryKey);
    if (!accountKeysetSession.current(uid)) {
      accountKeysetSession.install(await unlockAccountKeyset(uid, recoveryKey, data));
    }
  } finally {
    recoveryKey.fill(0);
  }
}

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
