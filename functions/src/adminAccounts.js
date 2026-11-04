// Admins: lets admins list and manage organization accounts.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {text, normalizedUsername, normalizedRole, validPassword, signInEmail, roleFromUser, isTemporaryEmail, profileComplete, defaultProfile, protectedAccount} = require("./accountPolicy");
const {readProfile, writeProfile, saveOrganizationLogin, requireAdmin, listEveryUser, usernameExistsGlobally} = require("./accountStorage");

exports.adminListAccounts = onCall(async (request) => {
  await requireAdmin(request);
  const users = await listEveryUser();
  const accounts = await Promise.all(users.map(async (user) => {
    const profile = await readProfile(user.uid);
    const role = roleFromUser(user, profile);
    const username = text(profile?.username || user.displayName || user.email?.split("@")[0], 24).toLowerCase();
    const staffSettings = role === "STAFF" ? (await db.collection("safetyStaff").doc(user.uid).get()).data() : null;
    return {
      uid: user.uid, username, role, fullName: text(profile?.fullName, 120), email: user.email || "",
      phone: text(profile?.phone || user.phoneNumber, 40), profileComplete: profileComplete(role, profile),
      protected: user.uid === request.auth.uid || protectedAccount(user, username),
      assignedUsers: role === "STAFF" ? (staffSettings?.activeCount || 0) : 0,
    };
  }));
  const counts = {USER: 0, SAFE_CONTACT: 0, STAFF: 0, ADMIN: 0};
  accounts.forEach((item) => { counts[item.role] += 1; });
  accounts.sort((a, b) => a.role.localeCompare(b.role) || a.username.localeCompare(b.username));
  return {counts, accounts};
});

exports.adminCreateAccount = onCall(async (request) => {
  await requireAdmin(request);
  const input = request.data || {};
  const username = normalizedUsername(input.username);
  const role = normalizedRole(input.role);
  const password = String(input.password || "");
  if (!validPassword(password, username)) throw new HttpsError("invalid-argument", "Temporary password must use 8–24 characters with a letter, number, and special character and must not contain the username.");
  // An admin may choose an organization login alias only while creating the account.
  const requestedAlias = role === "STAFF" || role === "ADMIN" ? text(input.email, 254) : "";
  const authEmail = signInEmail(username, role, requestedAlias);
  const profileEmail = requestedAlias ? authEmail : "";
  const directory = db.collection("accountDirectory").doc(username);
  if (await usernameExistsGlobally(username)) throw new HttpsError("already-exists", "That username is already in use.");
  let created;
  try {
    // Managed aliases are approved by the creating admin, so support assignment can use the account immediately.
    created = await auth.createUser({
      email: authEmail,
      password,
      displayName: username,
      emailVerified: role === "STAFF" || role === "ADMIN" || isTemporaryEmail(authEmail),
    });
    await auth.setCustomUserClaims(created.uid, {role});
    const profile = defaultProfile(username, role, input, profileEmail);
    await writeProfile(created.uid, profile);
    await directory.create({
      uid: created.uid,
      username,
      role,
      ...(role === "STAFF" || role === "ADMIN" ? {loginAlias: authEmail} : {}),
      createdAt: FieldValue.serverTimestamp(),
    });
    await saveOrganizationLogin(created.uid, username, role, profile.email, authEmail);
    return {uid: created.uid, username, role, email: authEmail, profileComplete: profileComplete(role, profile)};
  } catch (error) {
    const directoryOwner = await directory.get().catch(() => null);
    if (directoryOwner?.data()?.uid === created?.uid) await directory.delete().catch(() => {});
    if (created?.uid) await bucket.deleteFiles({prefix: `accounts/${created.uid}/`}).catch(() => {});
    if (created?.uid) await auth.deleteUser(created.uid).catch(() => {});
    if (error.code === "auth/email-already-exists" || error.code === 6 || error.code === "already-exists") {
      throw new HttpsError("already-exists", "That username is already in use.");
    }
    throw error;
  }
});
