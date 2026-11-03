// All account roles: stores account profiles and username claims.
const {HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {isTemporaryEmail, organizationEmailKey, signInEmail, roleFromUser, text} = require("./accountPolicy");

// Stores a private email-to-account lookup for staff and admin sign-in.
async function saveOrganizationLogin(uid, username, role, email, authEmail = signInEmail(username, role)) {
  if ((role !== "STAFF" && role !== "ADMIN") || !email || isTemporaryEmail(email)) return;
  const reference = db.collection("organizationLoginAliases").doc(organizationEmailKey(email));
  const existing = await reference.get();
  if (existing.exists && existing.data()?.uid !== uid) throw new HttpsError("already-exists", "That email is already used by another organization account.");
  await reference.set({
    uid, username, role, authEmail: signInEmail(username, role, authEmail), updatedAt: FieldValue.serverTimestamp(),
  });
}

// Reads the protected login alias chosen at account creation and adds the standard alias to older accounts.
async function lockedOrganizationAlias(uid, username, role) {
  if (role !== "STAFF" && role !== "ADMIN") {
    throw new HttpsError("invalid-argument", "Only staff and admin accounts have organization aliases.");
  }
  const reference = db.collection("accountDirectory").doc(username);
  const snapshot = await reference.get();
  const value = snapshot.data();
  if (!snapshot.exists || value?.uid !== uid || value?.role !== role) {
    throw new HttpsError("not-found", "The protected account directory entry is missing.");
  }
  const stored = text(value.loginAlias, 254).toLowerCase();
  const alias = stored ? signInEmail(username, role, stored) : signInEmail(username, role);
  if (!stored) await reference.set({loginAlias: alias}, {merge: true});
  // Older accounts are moved to the protected alias before the client retries sign-in.
  const user = await auth.getUser(uid);
  if (user.email?.toLowerCase() !== alias) {
    await auth.updateUser(uid, {email: alias, emailVerified: true, displayName: username});
  }
  return alias;
}

// Removes the private staff or admin email lookup when its account changes or is deleted.
async function deleteOrganizationLogin(email) {
  if (email && !isTemporaryEmail(email)) await db.collection("organizationLoginAliases").doc(organizationEmailKey(email)).delete().catch(() => {});
}

// Returns the protected Cloud Storage location for one account profile.
function profilePath(uid) { return `accounts/${uid}/profile.json`; }

// Downloads and parses one account profile, returning null when no profile exists.
async function readProfile(uid) {
  try {
    const [buffer] = await bucket.file(profilePath(uid)).download();
    return JSON.parse(buffer.toString("utf8"));
  } catch (error) {
    if (error.code === 404) return null;
    throw error;
  }
}

// Saves a readable versioned profile document in protected Cloud Storage.
async function writeProfile(uid, profile) {
  await bucket.file(profilePath(uid)).save(JSON.stringify(profile, null, 2), {
    resumable: false,
    contentType: "application/json",
    metadata: {metadata: {schemaVersion: "3", format: "pretty-json"}},
  });
}

// Rejects callable requests unless Firebase Auth proves that the caller is an admin.
async function requireAdmin(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const caller = await auth.getUser(request.auth.uid);
  const profile = await readProfile(caller.uid);
  if (roleFromUser(caller, profile) !== "ADMIN") throw new HttpsError("permission-denied", "Admin access is required.");
  return {caller, profile};
}

// Reads every Firebase Auth account one page at a time.
async function listEveryUser() {
  const users = [];
  let token;
  // Continues until Firebase returns no token for another page.
  do {
    const page = await auth.listUsers(1000, token);
    users.push(...page.users);
    token = page.pageToken;
  } while (token);
  return users;
}

// Checks every account store so two user types cannot claim the same username.
async function usernameExistsGlobally(username) {
  if ((await db.collection("accountDirectory").doc(username).get()).exists) return true;
  if ((await listEveryUser()).some((user) => String(user.displayName || "").trim().toLowerCase() === username)) return true;
  // Searches each historical internal-email domain used by older accounts.
  for (const domain of ["users", "safe", "staff", "admin"]) {
    try {
      await auth.getUserByEmail(`${username}@${domain}.olive.invalid`);
      return true;
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
  }
  return false;
}

module.exports = {saveOrganizationLogin, lockedOrganizationAlias, deleteOrganizationLogin, profilePath, readProfile, writeProfile, requireAdmin, listEveryUser, usernameExistsGlobally};
