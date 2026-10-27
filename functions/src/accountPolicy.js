// Contains the account policy code for this part of Olive.
const {HttpsError} = require("firebase-functions/v2/https");
const {createHash} = require("node:crypto");

const ROLES = new Set(["USER", "SAFE_CONTACT", "STAFF", "ADMIN"]);
const USERNAME = /^(?=.*[a-z])[a-z0-9_]{8,24}$/;
const EXISTING_USERNAME = /^[a-z0-9_]{4,24}$/;
const PASSWORD = /^(?=.*\p{L})(?=.*\p{N})(?=.*[^\p{L}\p{N}\s]).{8,24}$/u;
const PROTECTED_USERNAMES = new Set(["olive_admin", "oliveadmin", "olivestaff"]);

function protectedAccount(user, username) {
  return PROTECTED_USERNAMES.has(text(username, 24).toLowerCase()) ||
    user?.customClaims?.owner === true || user?.customClaims?.protected === true;
}


function validPassword(value, username) {
  const password = String(value || "");
  return PASSWORD.test(password) && (username === undefined ||
    !password.toLowerCase().includes(String(username).trim().toLowerCase()));
}


function text(value, max = 2000) {
  return String(value ?? "").trim().slice(0, max);
}

function normalizedUsername(value) {
  const username = text(value, 24).toLowerCase();
  if (!USERNAME.test(username)) throw new HttpsError("invalid-argument", "Username must use 8–24 letters, numbers, or underscores and include at least one letter.");
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

module.exports = {validPassword, text, normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail, organizationEmailKey, roleFromUser, isTemporaryEmail, profileComplete, defaultProfile, protectedAccount, PROTECTED_USERNAMES};
