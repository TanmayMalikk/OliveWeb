const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebase");
const {text, normalizedUsername, normalizedRole, validPassword, signInEmail, roleFromUser, isTemporaryEmail, profileComplete, defaultProfile, PROTECTED_USERNAME} = require("./accountPolicy");
const {readProfile, writeProfile, saveOrganizationLogin, requireAdmin, listEveryUser} = require("./accountStorage");

exports.adminListAccounts = onCall(async (request) => {
  await requireAdmin(request);
  const users = await listEveryUser();
  const accounts = await Promise.all(users.map(async (user) => {
    const profile = await readProfile(user.uid);
    const role = roleFromUser(user, profile);
    const username = text(profile?.username || user.displayName || user.email?.split("@")[0], 24).toLowerCase();
    return {
      uid: user.uid, username, role, fullName: text(profile?.fullName, 120), email: user.email || "",
      phone: text(profile?.phone || user.phoneNumber, 40), profileComplete: profileComplete(role, profile),
      protected: user.uid === request.auth.uid || username === PROTECTED_USERNAME || user.customClaims?.owner === true,
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
  // Staff and Admin always keep their username alias in Firebase Auth. Their real email belongs in the private profile.
  const authEmail = signInEmail(username, role);
  const profileEmail = (role === "STAFF" || role === "ADMIN") && text(input.email, 254) ? signInEmail(username, role, input.email) : authEmail;
  const directory = db.collection("accountDirectory").doc(username);
  if ((await directory.get()).exists) throw new HttpsError("already-exists", "That username is already in use.");
  if ((await listEveryUser()).some((user) => text(user.displayName, 24).toLowerCase() === username)) {
    throw new HttpsError("already-exists", "That username is already in use.");
  }
  for (const domain of ["users", "safe", "staff", "admin"]) {
    try { await auth.getUserByEmail(`${username}@${domain}.olive.invalid`); throw new HttpsError("already-exists", "That username is already in use."); }
    catch (error) { if (error instanceof HttpsError) throw error; if (error.code !== "auth/user-not-found") throw error; }
  }
  let created;
  try {
    created = await auth.createUser({email: authEmail, password, displayName: username, emailVerified: isTemporaryEmail(authEmail)});
    await auth.setCustomUserClaims(created.uid, {role});
    const profile = defaultProfile(username, role, input, profileEmail);
    await writeProfile(created.uid, profile);
    await directory.create({uid: created.uid, username, role, createdAt: FieldValue.serverTimestamp()});
    await saveOrganizationLogin(created.uid, username, role, profile.email);
    return {uid: created.uid, username, role, email: authEmail, profileComplete: profileComplete(role, profile)};
  } catch (error) {
    await directory.delete().catch(() => {});
    if (created?.uid) await bucket.deleteFiles({prefix: `accounts/${created.uid}/`}).catch(() => {});
    if (created?.uid) await auth.deleteUser(created.uid).catch(() => {});
    if (error.code === "auth/email-already-exists") throw new HttpsError("already-exists", "That email is already in use.");
    throw error;
  }
});
