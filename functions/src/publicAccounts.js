// Creates User and Safe Contact accounts while reserving one username across every account role.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, FieldValue} = require("./firebase");
const {normalizedUsername, normalizedRole, validPassword, signInEmail} = require("./accountPolicy");
const {usernameExistsGlobally} = require("./accountStorage");

exports.createPublicAccount = onCall(async (request) => {
  const input = request.data || {};
  const username = normalizedUsername(input.username);
  const role = normalizedRole(input.role);
  const password = String(input.password || "");
  if (role !== "USER" && role !== "SAFE_CONTACT") {
    throw new HttpsError("permission-denied", "Staff and Admin accounts must be created by an administrator.");
  }
  if (!validPassword(password, username)) {
    throw new HttpsError("invalid-argument", "Password must meet the account security rules.");
  }
  if (await usernameExistsGlobally(username)) {
    throw new HttpsError("already-exists", "That username is already in use.");
  }

  const directory = db.collection("accountDirectory").doc(username);
  let created;
  try {
    created = await auth.createUser({
      email: signInEmail(username, role),
      password,
      displayName: username,
      emailVerified: true,
    });
    await auth.setCustomUserClaims(created.uid, {role});
    await directory.create({
      uid: created.uid,
      username,
      role,
      createdAt: FieldValue.serverTimestamp(),
    });
    // The Android app can sign in with the email alias and password it just
    // supplied. This avoids requiring the function runtime to sign a second,
    // short-lived custom token after the account has already been created.
    return {uid: created.uid, username};
  } catch (error) {
    if (created?.uid) await auth.deleteUser(created.uid).catch(() => {});
    const directoryOwner = await directory.get().catch(() => null);
    if (directoryOwner?.data()?.uid === created?.uid) await directory.delete().catch(() => {});
    if (error.code === "auth/email-already-exists" || error.code === 6 || error.code === "already-exists") {
      throw new HttpsError("already-exists", "That username is already in use.");
    }
    throw error;
  }
});

