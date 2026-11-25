// All account roles: deletes an account and its stored records on request.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {text, roleFromUser, protectedAccount} = require("./accountPolicy");
const {readProfile, deleteOrganizationLogin, requireAdmin, listEveryUser} = require("./accountStorage");
const {saveSupportConversation, PLAN_CONSULT} = require("./supportGroups");
const {stripeKey, closeFitnessBilling} = require("./fitnessAiBilling");

// Runs async work in bounded batches so Firestore cleanup finishes well within callable timeouts.
async function runInBatches(items, batchSize, worker) {
  for (let index = 0; index < items.length; index += batchSize) {
    await Promise.all(items.slice(index, index + batchSize).map(worker));
  }
}

// Deletes every document returned by an account-cleanup query.
async function deleteQuery(query) {
  const snapshot = await query.get();
  await runInBatches(snapshot.docs, 10, (document) => db.recursiveDelete(document.ref));
}

// Removes the deleted account from friend subcollections owned by other users.
async function deleteFriendListReferences(uid) {
  // Parent friend-list documents may not exist, so list their references and query each subcollection.
  // This also repairs accounts whose earlier deletion stopped after removing the friendship document.
  const owners = await db.collection("friendLists").listDocuments();
  await runInBatches(owners, 25, async (owner) => {
    const references = await owner.collection("friends").where("friendUid", "==", uid).get();
    if (!references.empty) {
      await Promise.all(references.docs.map((reference) => reference.ref.delete()));
    }
  });
}

// Deletes private entries owned by the account and removes its key from entries shared by others.
async function deletePrivateEntryReferences(uid) {
  const privateEntries = await db.collection("privateEntries").where("allowedUsers", "array-contains", uid).get();
  await runInBatches(privateEntries.docs, 10, (entry) => (
    entry.get("ownerUid") === uid
      ? entry.ref.delete()
      : entry.ref.update({
        [`wrappedKeys.${uid}`]: FieldValue.delete(),
        allowedUsers: FieldValue.arrayRemove(uid),
        updatedAt: FieldValue.serverTimestamp(),
      })
  ));
}

// Removes a deleted staff assignment without deleting the user's safety plan.
async function clearStaffAssignments(uid) {
  const sharedPlans = await db.collection("safetyPlans").where("staffUid", "==", uid).get();
  await runInBatches(sharedPlans.docs, 10, (plan) => plan.ref.update({staffUid: "", staffName: ""}));
}

// Returns escalated plans to their staff conversation when an admin is deleted.
async function clearAdminAssignments(uid) {
  const escalatedPlans = await db.collection("safetyPlans").where("adminUid", "==", uid).get();
  await runInBatches(escalatedPlans.docs, 10, async (snapshot) => {
    const plan = {...snapshot.data(), adminUid: "", adminName: ""};
    await snapshot.ref.update({adminUid: "", adminName: ""});
    if (plan.staffUid) {
      await saveSupportConversation(
        {...plan, conversationId: plan.consultationConversationId, supportOrigin: PLAN_CONSULT},
        plan.supportSafeContactUid || "",
      );
    }
  });
}

// Lets an admin remove one non-protected account and all records that identify it.
exports.adminDeleteAccount = onCall({secrets: [stripeKey], timeoutSeconds: 120}, async (request) => {
  const {caller} = await requireAdmin(request);
  const uid = text(request.data?.uid, 128);
  if (!uid || uid === caller.uid) throw new HttpsError("failed-precondition", "You cannot delete the account you are using.");
  const target = await auth.getUser(uid);
  const profile = await readProfile(uid);
  const role = roleFromUser(target, profile);
  const username = text(profile?.username || target.displayName || target.email?.split("@")[0], 24).toLowerCase();
  if (protectedAccount(target, username)) throw new HttpsError("permission-denied", "This protected account cannot be deleted.");
  // Keeps at least one administrator available to manage organization accounts.
  if (role === "ADMIN") {
    const users = await listEveryUser();
    let adminCount = 0;
    // Counts trusted role claims instead of editable profile labels.
    for (const user of users) if (roleFromUser(user, await readProfile(user.uid)) === "ADMIN") adminCount++;
    if (adminCount <= 1) throw new HttpsError("failed-precondition", "The last Admin account cannot be deleted.");
  }
  await closeFitnessBilling(uid);
  await Promise.all([
    db.recursiveDelete(db.collection("safetyPlans").doc(uid)),
    db.recursiveDelete(db.collection("supportRequests").doc(uid)),
    db.recursiveDelete(db.collection("accountKeysets").doc(uid)),
    db.recursiveDelete(db.collection("accountKeyDirectories").doc(uid)),
    db.recursiveDelete(db.collection("accountRecoveryPasskeys").doc(uid)),
    db.recursiveDelete(db.collection("accountRecoveryOpaqueRecords").doc(uid)),
    db.recursiveDelete(db.collection("accountRecoveryOpaqueThrottle").doc(uid)),
    deleteQuery(db.collection("supportRequests").where("ownerUid", "==", uid)),
    db.collection("safetyStaff").doc(uid).delete(),
    deleteQuery(db.collection("friendInvites").where("ownerUid", "==", uid)),
    db.collection("friendInviteOwners").doc(uid).delete(),
    db.collection("friendInviteAttempts").doc(uid).delete(),
    db.recursiveDelete(db.collection("messagingPeerAccess").doc(uid)).catch(() => {}),
    deleteQuery(db.collection("friendRequests").where("senderUid", "==", uid)),
    deleteQuery(db.collection("friendRequests").where("recipientUid", "==", uid)),
    deleteQuery(db.collection("friendships").where("participantIds", "array-contains", uid)),
    deleteQuery(db.collection("conversations").where("participantIds", "array-contains", uid)),
    deletePrivateEntryReferences(uid),
    deleteFriendListReferences(uid),
    db.recursiveDelete(db.collection("friendLists").doc(uid)).catch(() => {}),
    db.recursiveDelete(db.collection("notificationTokens").doc(uid)).catch(() => {}),
    db.recursiveDelete(db.collection("accountLoginDevices").doc(uid)).catch(() => {}),
    db.collection("liveLocationSessions").doc(uid).delete().catch(() => {}),
    db.collection("emailCooldowns").doc(`safety-plan--${uid}`).delete().catch(() => {}),
    deleteQuery(db.collection("emailNotificationLogs").where("uid", "==", uid)).catch(() => {}),
    db.collection("users").doc(uid).delete().catch(() => {}),
  ]);
  await Promise.all([
    clearStaffAssignments(uid),
    clearAdminAssignments(uid),
  ]);
  // Frees the global username only after its conversations and private records are cleaned up.
  await Promise.all([
    ...(username ? [
      db.collection("messageDirectory").doc(username).delete().catch(() => {}),
      db.collection("accountDirectory").doc(username).delete().catch(() => {}),
    ] : []),
    deleteOrganizationLogin(profile?.email),
    bucket.deleteFiles({prefix: `accounts/${uid}/`}),
  ]);
  await auth.deleteUser(uid);
  return {deleted: true, uid};
});
