const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {setGlobalOptions} = require("firebase-functions/v2");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getFirestore, FieldValue} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {createHash} = require("node:crypto");

initializeApp();
setGlobalOptions({region: "northamerica-northeast1", maxInstances: 10});

const auth = getAuth();
const db = getFirestore();
const bucket = getStorage().bucket();
const ROLES = new Set(["USER", "SAFE_CONTACT", "STAFF", "ADMIN"]);
const USERNAME = /^[a-z0-9_]{8,24}$/;
const EXISTING_USERNAME = /^[a-z0-9_]{4,24}$/;
const PASSWORD = /^(?=.*\p{L})(?=.*\p{N})(?=.*[^\p{L}\p{N}\s]).{8,24}$/u;
function validPassword(value) { return PASSWORD.test(String(value || "")); }
const PROTECTED_USERNAME = "olive_admin";

function text(value, max = 2000) {
  return String(value ?? "").trim().slice(0, max);
}
function normalizedUsername(value) {
  const username = text(value, 24).toLowerCase();
  if (!USERNAME.test(username)) throw new HttpsError("invalid-argument", "Username must use 8–24 letters, numbers, or underscores.");
  return username;
}
function normalizedExistingUsername(value) {
  const username = text(value, 24).toLowerCase();
  if (!EXISTING_USERNAME.test(username)) throw new HttpsError("invalid-argument", "That account username is invalid.");
  return username;
}
function normalizedRole(value) {
  const role = text(value, 20).toUpperCase();
  if (!ROLES.has(role)) throw new HttpsError("invalid-argument", "Choose a valid account type.");
  return role;
}
function signInEmail(username, role, suppliedEmail = "") {
  const email = text(suppliedEmail, 254).toLowerCase();
  if ((role === "STAFF" || role === "ADMIN") && email) {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpsError("invalid-argument", "Enter a valid email address.");
    return email;
  }
  const domain = {USER: "users", SAFE_CONTACT: "safe", STAFF: "staff", ADMIN: "admin"}[role];
  return `${username}@${domain}.olive.invalid`;
}
function organizationEmailKey(email) {
  return createHash("sha256").update(text(email, 254).toLowerCase()).digest("hex");
}
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
function roleFromUser(user, profile) {
  const claim = user.customClaims?.role;
  if (ROLES.has(claim)) return claim;
  if (ROLES.has(profile?.role)) return profile.role;
  const domain = (user.email || "").split("@")[1] || "";
  return domain === "safe.olive.invalid" ? "SAFE_CONTACT" :
    domain === "staff.olive.invalid" ? "STAFF" :
      domain === "admin.olive.invalid" ? "ADMIN" : "USER";
}
function isTemporaryEmail(email) { return /@(users|safe|staff|admin)\.olive\.invalid$/i.test(email || ""); }
function profileComplete(role, profile) {
  if (!profile) return false;
  if (role === "STAFF" || role === "ADMIN") return Boolean(text(profile.fullName, 120) && /^\d{4}-\d{2}-\d{2}$/.test(profile.dob || "") && text(profile.email, 254) && !isTemporaryEmail(profile.email));
  return Boolean(text(profile.fullName, 120) && /^\d{4}-\d{2}-\d{2}$/.test(profile.dob || "") && text(profile.language, 60) && text(profile.address?.city, 160));
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
function defaultProfile(username, role, input, email) {
  const address = input.address && typeof input.address === "object" ? input.address : {};
  return {
    username,
    fullName: text(input.fullName, 120),
    affectedNames: role === "USER" ? text(input.affectedNames, 500) : "",
    abuserName: role === "USER" ? text(input.abuserName, 120) : "",
    dob: text(input.dob, 10),
    language: role === "USER" || role === "SAFE_CONTACT" ? (text(input.language, 60) || "English") : "English",
    situation: role === "USER" ? text(input.situation, 2000) : "",
    region: text(input.region, 200),
    triggerHash: "",
    contacts: [],
    address: {
      line1: text(address.line1, 160), line2: text(address.line2, 160), city: text(address.city, 160),
      province: text(address.province, 160), postalCode: text(address.postalCode, 160), country: text(address.country, 160) || "Canada",
    },
    coverType: "FOOD",
    role,
    email: role === "STAFF" || role === "ADMIN" ? (isTemporaryEmail(email) ? "" : email) : "",
    phone: text(input.phone, 40),
  };
}

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
  if (!validPassword(password)) throw new HttpsError("invalid-argument", "Temporary password must use 8–24 characters with a letter, number, and special character.");
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

// Exact real-email lookup returns only the private Firebase alias needed for password authentication.
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

// Existing organization accounts may have had their Auth email replaced by a real email. One authenticated call restores username login.
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
  await deleteQuery(db.collection("friendRequests").where("senderUid", "==", uid));
  await deleteQuery(db.collection("friendRequests").where("recipientUid", "==", uid));
  await deleteQuery(db.collection("friendships").where("participantIds", "array-contains", uid));
  await deleteQuery(db.collection("conversations").where("participantIds", "array-contains", uid));
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

exports._test = {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail, organizationEmailKey, isTemporaryEmail, profileComplete, validPassword};
