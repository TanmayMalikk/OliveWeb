const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebase");
const {text, roleFromUser, PROTECTED_USERNAME} = require("./accountPolicy");
const {readProfile, deleteOrganizationLogin, requireAdmin, listEveryUser} = require("./accountStorage");

async function deleteQuery(query) {
  const snapshot = await query.get();
  // Keep cleanup sequential so large accounts do not exhaust Firestore stream listeners.
  for (const document of snapshot.docs) await db.recursiveDelete(document.ref);
}

async function deleteFriendListReferences(uid) {
  // Parent friend-list documents may not exist, so list their references and query each subcollection.
  // This also repairs accounts whose earlier deletion stopped after removing the friendship document.
  const owners = await db.collection("friendLists").listDocuments();
  for (const owner of owners) {
    const references = await owner.collection("friends").where("friendUid", "==", uid).get();
    for (const reference of references.docs) await reference.ref.delete();
  }
}

exports.adminDeleteAccount = onCall(async (request) => {
  const {caller} = await requireAdmin(request);
  const uid = text(request.data?.uid, 128);
  if (!uid || uid === caller.uid) throw new HttpsError("failed-precondition", "You cannot delete the account you are using.");
  const target = await auth.getUser(uid);
  const profile = await readProfile(uid);
  const role = roleFromUser(target, profile);
  const username = text(profile?.username || target.displayName || target.email?.split("@")[0], 24).toLowerCase();
  if (username === PROTECTED_USERNAME || target.customClaims?.owner === true) throw new HttpsError("permission-denied", "The owner account cannot be deleted.");
  if (role === "ADMIN") {
    const users = await listEveryUser();
    let adminCount = 0;
    for (const user of users) if (roleFromUser(user, await readProfile(user.uid)) === "ADMIN") adminCount++;
    if (adminCount <= 1) throw new HttpsError("failed-precondition", "The last Admin account cannot be deleted.");
  }
  await db.recursiveDelete(db.collection("safetyPlans").doc(uid));
  const sharedPlans = await db.collection("safetyPlans").where("staffUid", "==", uid).get();
  for (const plan of sharedPlans.docs) await plan.ref.update({staffUid: "", staffName: "", consultationRequested: true});
  await db.collection("safetyStaff").doc(uid).delete();
  await deleteQuery(db.collection("friendRequests").where("senderUid", "==", uid));
  await deleteQuery(db.collection("friendRequests").where("recipientUid", "==", uid));
  await deleteQuery(db.collection("friendships").where("participantIds", "array-contains", uid));
  await deleteQuery(db.collection("conversations").where("participantIds", "array-contains", uid));
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
  if (username) {
    await db.collection("messageDirectory").doc(username).delete().catch(() => {});
    await db.collection("accountDirectory").doc(username).delete().catch(() => {});
  }
  await deleteOrganizationLogin(profile?.email);
  await bucket.deleteFiles({prefix: `accounts/${uid}/`});
  await auth.deleteUser(uid);
  return {deleted: true, uid};
});
