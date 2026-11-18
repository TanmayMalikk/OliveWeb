// Grants key reads only for established connections and current conversation members.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {db} = require("./firebaseAdminClient");
const {safetyCaller} = require("./safetyPlanAccess");

exports.messagingAuthorizePeer = onCall({enforceAppCheck: true}, async request => {
  const caller = await safetyCaller(request);
  const username = typeof request.data?.username === "string" ? request.data.username.trim().toLowerCase() : "";
  if (!/^[a-z0-9_]{4,24}$/.test(username)) throw new HttpsError("invalid-argument", "Invalid messaging identity.");
  const row = await db.collection("messageDirectory").doc(username).get();
  const uid = row.get("uid");
  if (!uid) throw new HttpsError("permission-denied", "This connection is unavailable. Use an invite code to connect.");
  const friendship = await db.collection("friendships").doc([caller.uid, uid].sort().join("--")).get();
  if (uid === caller.uid || friendship.exists || ["STAFF", "ADMIN"].includes(caller.role)) return {uid};
  const organization = await db.collection("accountDirectory").doc(username).get();
  const grant = db.collection("messagingPeerAccess").doc(caller.uid).collection("peers").doc(uid);
  if (["STAFF", "ADMIN"].includes(organization.get("role")) && organization.get("uid") === uid) {
    await grant.set({organizationUsername: username});
    return {uid};
  }
  const conversations = await db.collection("conversations").where("participantIds", "array-contains", caller.uid).get();
  const shared = conversations.docs.find(item => item.get("participantIds")?.includes(uid));
  if (!shared) throw new HttpsError("permission-denied", "This connection is unavailable. Use an invite code to connect.");
  await grant.set({conversationId: shared.id});
  return {uid};
});
