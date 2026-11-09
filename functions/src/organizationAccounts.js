// Admins: creates and updates staff and admin accounts.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db} = require("./firebaseAdminClient");
const {text, normalizedExistingUsername, normalizedRole, signInEmail, organizationEmailKey, roleFromUser, isTemporaryEmail, defaultProfile, profileComplete} = require("./accountPolicy");
const {readProfile, writeProfile, saveOrganizationLogin, lockedOrganizationAlias, deleteOrganizationLogin} = require("./accountStorage");

exports.completeOrganizationProfile = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const user = await auth.getUser(request.auth.uid);
  const current = await readProfile(user.uid);
  const role = roleFromUser(user, current);
  if (role !== "STAFF" && role !== "ADMIN") throw new HttpsError("permission-denied", "This setup is for Staff and Admin accounts.");
  const fullName = text(request.data?.fullName, 120);
  const dob = text(request.data?.dob, 10);
  const phone = text(request.data?.phone, 40);
  const email = text(request.data?.email, 254).toLowerCase();
  if (!fullName || !/^\d{4}-\d{2}-\d{2}$/.test(dob) || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || isTemporaryEmail(email)) throw new HttpsError("invalid-argument", "Full name, date of birth, and a real contact email are required.");
  // Profile setup may change contact details, but it never changes the protected login alias.
  await auth.updateUser(user.uid, {displayName: current?.username || user.displayName});
  await auth.setCustomUserClaims(user.uid, {...user.customClaims, role});
  const profile = {...defaultProfile(current?.username || user.displayName, role, {...current, fullName, dob, phone}, email), ...current, fullName, dob, phone, email, role};
  const alias = await lockedOrganizationAlias(user.uid, profile.username, role);
  await saveOrganizationLogin(user.uid, profile.username, role, email, alias);
  await writeProfile(user.uid, profile);
  // Keeps the lightweight admin directory current without exposing the protected profile file.
  await db.collection("accountDirectory").doc(profile.username).set({
    fullName: profile.fullName,
    contactEmail: profile.email,
    phone: profile.phone,
    profileComplete: profileComplete(role, profile),
  }, {merge: true});
  if (current?.email?.toLowerCase() !== email.toLowerCase()) await deleteOrganizationLogin(current?.email);
  return {profile};
});

exports.resolveOrganizationLogin = onCall(async (request) => {
  const identifier = text(request.data?.identifier || request.data?.email, 254).toLowerCase();
  const role = normalizedRole(request.data?.role);
  if (role !== "STAFF" && role !== "ADMIN") {
    throw new HttpsError("invalid-argument", "Choose a staff or admin account type.");
  }
  if (!identifier.includes("@")) {
    const username = normalizedExistingUsername(identifier);
    const directory = await db.collection("accountDirectory").doc(username).get();
    const value = directory.data();
    if (!directory.exists || value?.role !== role) throw new HttpsError("not-found", "No matching organization login was found.");
    return {authEmail: await lockedOrganizationAlias(value.uid, username, role)};
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(identifier)) {
    throw new HttpsError("invalid-argument", "Enter a valid organization email or username.");
  }
  const snapshot = await db.collection("organizationLoginAliases").doc(organizationEmailKey(identifier)).get();
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
  const alias = await lockedOrganizationAlias(user.uid, username, role);
  await saveOrganizationLogin(user.uid, username, role, profile?.email, alias);
  if (user.email?.toLowerCase() === alias) return {changed: false, alias};
  await auth.updateUser(user.uid, {email: alias, emailVerified: true, displayName: username});
  return {changed: true, alias};
});
