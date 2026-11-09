// All account roles: deletes an account and its stored records on request.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {text, roleFromUser, protectedAccount} = require("./accountPolicy");
const {readProfile, deleteOrganizationLogin, requireAdmin, listEveryUser} = require("./accountStorage");
const {saveSupportConversation, PLAN_CONSULT} = require("./supportGroups");

// Deletes every document returned by an account-cleanup query.
async function deleteQuery(query) {
  const snapshot = await query.get();
  // Keep cleanup sequential so large accounts do not exhaust Firestore stream listeners.
  for (const document of snapshot.docs) await db.recursiveDelete(document.ref);
}

// Removes the deleted account from friend subcollections owned by other users.
async function deleteFriendListReferences(uid) {
  // Parent friend-list documents may not exist, so list their references and query each subcollection.
  // This also repairs accounts whose earlier deletion stopped after removing the friendship document.
  const owners = await db.collection("friendLists").listDocuments();
  for (const owner of owners) {
    const references = await owner.collection("friends").where("friendUid", "==", uid).get();
    for (const reference of references.docs) await reference.ref.delete();
  }
}

// Lets an admin remove one non-protected account and all records that identify it.
exports.adminDeleteAccount = onCall(async (request) => {
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
  await db.recursiveDelete(db.collection("safetyPlans").doc(uid));
  await db.recursiveDelete(db.collection("supportRequests").doc(uid));
  await deleteQuery(db.collection("supportRequests").where("ownerUid", "==", uid));
  // Removes a deleted staff assignment without deleting the user's safety plan.
  const sharedPlans = await db.collection("safetyPlans").where("staffUid", "==", uid).get();
  for (const plan of sharedPlans.docs) await plan.ref.update({staffUid: "", staffName: ""});
  // Returns escalated plans to their staff conversation when an admin is deleted.
  const escalatedPlans = await db.collection("safetyPlans").where("adminUid", "==", uid).get();
  for (const snapshot of escalatedPlans.docs) {
    const plan = {...snapshot.data(), adminUid: "", adminName: ""};
    await snapshot.ref.update({adminUid: "", adminName: ""});
    if (plan.staffUid) await saveSupportConversation(
        {...plan, conversationId: plan.consultationConversationId, supportOrigin: PLAN_CONSULT},
        plan.supportSafeContactUid || "");
  }
  await db.collection("safetyStaff").doc(uid).delete();
  await deleteQuery(db.collection("friendRequests").where("senderUid", "==", uid));
  await deleteQuery(db.collection("friendRequests").where("recipientUid", "==", uid));
  await deleteQuery(db.collection("friendships").where("participantIds", "array-contains", uid));
  await deleteQuery(db.collection("conversations").where("participantIds", "array-contains", uid));
  // Deletes entries owned by the account and removes its key from entries shared by others.
  const privateEntries = await db.collection("privateEntries").where("allowedUsers", "array-contains", uid).get();
  for (const entry of privateEntries.docs) {
    if (entry.get("ownerUid") === uid) await entry.ref.delete();
    else await entry.ref.update({
      [`wrappedKeys.${uid}`]: FieldValue.delete(),
      allowedUsers: FieldValue.arrayRemove(uid),
      updatedAt: FieldValue.serverTimestamp(),
    });
  }
  await deleteFriendListReferences(uid);
  await db.recursiveDelete(db.collection("friendLists").doc(uid)).catch(() => {});
  await db.collection("users").doc(uid).delete().catch(() => {});
  // Frees the global username only after its conversations and private records are cleaned up.
  if (username) {
    await db.collection("messageDirectory").doc(username).delete().catch(() => {});
    await db.collection("accountDirectory").doc(username).delete().catch(() => {});
  }
  await deleteOrganizationLogin(profile?.email);
  await bucket.deleteFiles({prefix: `accounts/${uid}/`});
  await auth.deleteUser(uid);
  return {deleted: true, uid};
});
