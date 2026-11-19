// Personal accounts connect through expiring, single-use invitations.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {randomBytes, createHash} = require("node:crypto");
const {db, auth, FieldValue} = require("./firebaseAdminClient");
const {safetyCaller} = require("./safetyPlanAccess");
const {roleFromUser} = require("./accountPolicy");

const LIFETIME = 48 * 60 * 60 * 1000;
const options = {enforceAppCheck: true};
const hash = code => createHash("sha256").update(code).digest("hex");
const unavailable = () => new HttpsError("failed-precondition", "This invite has expired, was refreshed, or has already been used.");
function active(invite, now) {
  return Boolean(invite && invite.expiresAt > now && !invite.claimedBy && !invite.revokedAt);
}
async function personalCaller(request) {
  const caller = await safetyCaller(request);
  if (!["USER", "SAFE_CONTACT"].includes(caller.role)) {
    throw new HttpsError("permission-denied", "Invitations are for users and safe contacts.");
  }
  return caller;
}
function presentation(value) {
  return {code: value.code, expiresAt: value.expiresAt,
    link: `https://yourolivebranch.ca/get/?invite=${value.code}`};
}

async function currentInvite(request, create) {
  const caller = await personalCaller(request);
  const owner = db.collection("friendInviteOwners").doc(caller.uid);
  const now = Date.now();
  const code = randomBytes(9).toString("base64url");
  const codeHash = hash(code);
  return db.runTransaction(async tx => {
    const previous = (await tx.get(owner)).data();
    if (active(previous, now) && (!create || request.data?.refresh !== true)) return presentation(previous);
    if (!create) return null;
    const reference = db.collection("friendInvites").doc(codeHash);
    if ((await tx.get(reference)).exists) throw new HttpsError("aborted", "Please generate the invite again.");
    const value = {ownerUid: caller.uid, code, codeHash, expiresAt: now + LIFETIME, claimedBy: "", revokedAt: 0};
    if (previous?.codeHash && active(previous, now)) {
      tx.update(db.collection("friendInvites").doc(previous.codeHash), {revokedAt: now});
    }
    tx.create(reference, {ownerUid: caller.uid, expiresAt: value.expiresAt, claimedBy: "", revokedAt: 0});
    tx.set(owner, value);
    return presentation(value);
  });
}

async function claimInvite(request) {
  const caller = await personalCaller(request);
  const code = typeof request.data?.code === "string" ? request.data.code.trim() : "";
  if (!/^[A-Za-z0-9_-]{8,32}$/.test(code)) throw new HttpsError("invalid-argument", "Enter a valid invite code.");
  // Count rejected attempts too. No invite details are exposed to unauthenticated visitors.
  const attempt = db.collection("friendInviteAttempts").doc(caller.uid);
  await db.runTransaction(async tx => {
    const now = Date.now();
    const previous = (await tx.get(attempt)).data();
    const sameWindow = previous?.windowStart > now - 15 * 60 * 1000;
    if (sameWindow && previous.count >= 60) throw new HttpsError("resource-exhausted", "Too many attempts. Try again in 15 minutes.");
    tx.set(attempt, {windowStart: sameWindow ? previous.windowStart : now, count: sameWindow ? previous.count + 1 : 1});
  });
  const reference = db.collection("friendInvites").doc(hash(code));
  const initial = (await reference.get()).data();
  if (!initial || initial.ownerUid === caller.uid) {
    if (initial?.ownerUid === caller.uid) throw new HttpsError("invalid-argument", "You cannot use your own invite.");
    throw unavailable();
  }
  const ownerUser = await auth.getUser(initial.ownerUid).catch(() => null);
  if (!ownerUser || ownerUser.disabled || !["USER", "SAFE_CONTACT"].includes(roleFromUser(ownerUser))) throw unavailable();
  const [ownerDirectory, callerDirectory] = await Promise.all([
    db.collection("accountDirectory").where("uid", "==", initial.ownerUid).limit(1).get(),
    db.collection("accountDirectory").where("uid", "==", caller.uid).limit(1).get(),
  ]);
  const ownerName = ownerDirectory.docs[0]?.get("username");
  const callerName = callerDirectory.docs[0]?.get("username");
  if (!ownerName || !callerName) throw new HttpsError("failed-precondition", "Finish setting up your account first.");
  const participants = [caller.uid, initial.ownerUid].sort();
  const friendshipId = participants.join("--");
  const ownerReference = db.collection("friendInviteOwners").doc(initial.ownerUid);
  const friendship = db.collection("friendships").doc(friendshipId);
  const conversation = db.collection("conversations").doc(friendshipId);
  return db.runTransaction(async tx => {
    const [invite, owner, existingFriendship, existingConversation] = await Promise.all([
      tx.get(reference), tx.get(ownerReference), tx.get(friendship), tx.get(conversation),
    ]);
    const value = invite.data();
    // A network retry by the successful claimant returns its previous result.
    if (value?.claimedBy === caller.uid) {
      tx.delete(attempt);
      return {friendshipId, username: ownerName};
    }
    if (!active(value, Date.now()) || owner.get("codeHash") !== hash(code)) throw unavailable();
    const names = {[caller.uid]: callerName, [initial.ownerUid]: ownerName};
    if (!existingFriendship.exists) tx.create(friendship, {
      participantIds: participants, participantUsernames: names, acceptedRequestId: `invite-${hash(code)}`,
      createdAt: FieldValue.serverTimestamp(),
    });
    for (const [uid, peerUid, username] of [[caller.uid, initial.ownerUid, ownerName], [initial.ownerUid, caller.uid, callerName]]) {
      tx.set(db.collection("friendLists").doc(uid).collection("friends").doc(friendshipId), {
        friendshipId, friendUid: peerUid, friendUsername: username, createdAt: FieldValue.serverTimestamp(),
      });
    }
    if (!existingConversation.exists) tx.create(conversation, {
      friendshipId, participantIds: participants, participantUsernames: names,
      createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
      lastMessage: "", lastMessageId: "", lastSenderUid: "",
    });
    tx.delete(db.collection("friendRequests").doc(`${caller.uid}--${initial.ownerUid}`));
    tx.delete(db.collection("friendRequests").doc(`${initial.ownerUid}--${caller.uid}`));
    tx.update(reference, {claimedBy: caller.uid, claimedAt: FieldValue.serverTimestamp()});
    tx.update(ownerReference, {claimedBy: caller.uid});
    tx.delete(attempt);
    return {friendshipId, username: ownerName};
  });
}

exports.friendInviteCurrent = onCall(options, request => currentInvite(request, false));
exports.friendInviteCreate = onCall(options, request => currentInvite(request, true));
exports.friendInviteClaim = onCall(options, claimInvite);
exports._test = {active, currentInvite, claimInvite, LIFETIME};
