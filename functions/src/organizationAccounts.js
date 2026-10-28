// Admins: creates and updates staff and admin accounts.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db} = require("./firebaseAdminClient");
const {text, normalizedExistingUsername, normalizedRole, signInEmail, organizationEmailKey, roleFromUser, isTemporaryEmail, defaultProfile} = require("./accountPolicy");
const {readProfile, writeProfile, saveOrganizationLogin, deleteOrganizationLogin} = require("./accountStorage");

exports.completeOrganizationProfile = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const user = await auth.getUser(request.auth.uid);
  const current = await readProfile(user.uid);
  const role = roleFromUser(user, current);
  if (role !== "STAFF" && role !== "ADMIN") throw new HttpsError("permission-denied", "This setup is for Staff and Admin accounts.");
  const fullName = text(request.data?.fullName, 120);
  const dob = text(request.data?.dob, 10);
  const phone = text(request.data?.phone, 40);
  const email = signInEmail(current?.username || user.displayName || "", role, request.data?.email);
  if (!fullName || !/^\d{4}-\d{2}-\d{2}$/.test(dob) || isTemporaryEmail(email)) throw new HttpsError("invalid-argument", "Full name, date of birth, and a real email are required.");
  // Keep the username alias as the Auth identifier; the real contact email is stored only in the profile.
  await auth.updateUser(user.uid, {displayName: current?.username || user.displayName});
  await auth.setCustomUserClaims(user.uid, {...user.customClaims, role});
  const profile = {...defaultProfile(current?.username || user.displayName, role, {...current, fullName, dob, phone}, email), ...current, fullName, dob, phone, email, role};
  await saveOrganizationLogin(user.uid, profile.username, role, email);
  await writeProfile(user.uid, profile);
  if (current?.email?.toLowerCase() !== email.toLowerCase()) await deleteOrganizationLogin(current?.email);
  return {profile};
});

exports.resolveOrganizationLogin = onCall(async (request) => {
  const email = text(request.data?.email, 254).toLowerCase();
  const role = normalizedRole(request.data?.role);
  if (role !== "STAFF" && role !== "ADMIN" || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new HttpsError("invalid-argument", "Enter a valid organization email and account type.");
  }
  const snapshot = await db.collection("organizationLoginAliases").doc(organizationEmailKey(email)).get();
  const value = snapshot.data();
  if (!snapshot.exists || value?.role !== role) throw new HttpsError("not-found", "No matching organization login was found.");
  return {authEmail: value.authEmail};
});

exports.restoreOrganizationLoginAlias = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const user = await auth.getUser(request.auth.uid);
  const profile = await readProfile(user.uid);
  const role = roleFromUser(user, profile);
  if (role !== "STAFF" && role !== "ADMIN") return {changed: false};
  const username = normalizedExistingUsername(profile?.username || user.displayName);
  const alias = signInEmail(username, role);
  await saveOrganizationLogin(user.uid, username, role, profile?.email);
  if (user.email?.toLowerCase() === alias) return {changed: false, alias};
  await auth.updateUser(user.uid, {email: alias, emailVerified: true, displayName: username});
  return {changed: true, alias};
});
