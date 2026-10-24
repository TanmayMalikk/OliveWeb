// Contains the account storage code for this part of Olive.
const {HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebase");
const {isTemporaryEmail, organizationEmailKey, signInEmail, roleFromUser} = require("./accountPolicy");

async function saveOrganizationLogin(uid, username, role, email) {
  if ((role !== "STAFF" && role !== "ADMIN") || !email || isTemporaryEmail(email)) return;
  const reference = db.collection("organizationLoginAliases").doc(organizationEmailKey(email));
  const existing = await reference.get();
  if (existing.exists && existing.data()?.uid !== uid) throw new HttpsError("already-exists", "That email is already used by another organization account.");
  await reference.set({
    uid, username, role, authEmail: signInEmail(username, role), updatedAt: FieldValue.serverTimestamp(),
  });
}

async function deleteOrganizationLogin(email) {
  if (email && !isTemporaryEmail(email)) await db.collection("organizationLoginAliases").doc(organizationEmailKey(email)).delete().catch(() => {});
}

function profilePath(uid) { return `accounts/${uid}/profile.json`; }

async function readProfile(uid) {
  try {
    const [buffer] = await bucket.file(profilePath(uid)).download();
    return JSON.parse(buffer.toString("utf8"));
  } catch (error) {
    if (error.code === 404) return null;
    throw error;
  }
}

async function writeProfile(uid, profile) {
  await bucket.file(profilePath(uid)).save(JSON.stringify(profile, null, 2), {
    resumable: false,
    contentType: "application/json",
    metadata: {metadata: {schemaVersion: "3", format: "pretty-json"}},
  });
}

async function requireAdmin(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const caller = await auth.getUser(request.auth.uid);
  const profile = await readProfile(caller.uid);
  if (roleFromUser(caller, profile) !== "ADMIN") throw new HttpsError("permission-denied", "Admin access is required.");
  return {caller, profile};
}

async function listEveryUser() {
  const users = [];
  let token;
  do {
    const page = await auth.listUsers(1000, token);
    users.push(...page.users);
    token = page.pageToken;
  } while (token);
  return users;
}

module.exports = {saveOrganizationLogin, deleteOrganizationLogin, profilePath, readProfile, writeProfile, requireAdmin, listEveryUser};
