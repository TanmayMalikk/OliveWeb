// All account roles: checks account rules.
const { HttpsError } = require("firebase-functions/v2/https");
const { createHash } = require("node:crypto");

const ROLES = new Set(["USER", "SAFE_CONTACT", "STAFF", "ADMIN"]);
const USERNAME = /^(?=.*[a-z])[a-z0-9_]{8,24}$/;
const EXISTING_USERNAME = /^[a-z0-9_]{4,24}$/;
const PASSWORD = /^(?=.*\p{L})(?=.*\p{N})(?=.*[^\p{L}\p{N}\s]).{8,24}$/u;
const PROTECTED_USERNAMES = new Set([
  "demoadmin",
  "demostaff",
  "olive_admin",
  "oliveadmin",
  "olivestaff",
]);

// Prevents demo, owner, and protected organization accounts from being deleted.
function protectedAccount(user, username) {
  return (
    PROTECTED_USERNAMES.has(text(username, 24).toLowerCase()) ||
    user?.customClaims?.owner === true ||
    user?.customClaims?.protected === true
  );
}

// Checks new account passwords without allowing the username inside the password.
function validPassword(value, username) {
  if (typeof value !== "string" || (username !== undefined && typeof username !== "string")) {
    return false;
  }
  const password = value;
  return (
    PASSWORD.test(password) &&
    (username === undefined ||
      !password.toLowerCase().includes(String(username).trim().toLowerCase()))
  );
}

// Converts untrusted account input into trimmed text with a safe maximum length.
function text(value, max = 2000) {
  return String(value ?? "")
    .trim()
    .slice(0, max);
}

// Validates and normalizes a username submitted during new account creation.
function normalizedUsername(value) {
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", "Username must be text.");
  }
  const username = value.trim().toLowerCase();
  if (!USERNAME.test(username)) {
    throw new HttpsError(
      "invalid-argument",
      "Username must use 8–24 letters, numbers, or underscores and include at least one letter.",
    );
  }
  return username;
}

// Accepts the shorter username rules used by accounts created before the current policy.
function normalizedExistingUsername(value) {
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", "Username must be text.");
  }
  const username = value.trim().toLowerCase();
  if (!EXISTING_USERNAME.test(username)) {
    throw new HttpsError(
      "invalid-argument",
      "That account username is invalid.",
    );
  }
  return username;
}

// Converts an account type into one of the four roles understood by the server.
function normalizedRole(value) {
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", "Account type must be text.");
  }
  const role = value.trim().toUpperCase();
  if (!ROLES.has(role)) {
    throw new HttpsError("invalid-argument", "Choose a valid account type.");
  }
  return role;
}

// Uses the alias chosen by an admin, or gives staff and admins the standard Olive Branch alias.
function signInEmail(username, role, suppliedEmail = "") {
  const email = text(suppliedEmail, 254).toLowerCase();
  if ((role === "STAFF" || role === "ADMIN") && email) {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      throw new HttpsError("invalid-argument", "Enter a valid email address.");
    }
    return email;
  }
  const domain = {
    USER: "users",
    SAFE_CONTACT: "safe",
    STAFF: null,
    ADMIN: null,
  }[role];
  if (role === "STAFF" || role === "ADMIN") {
    return `${username}@yourolivebranch.ca`;
  }
  return `${username}@${domain}.olive.invalid`;
}

// Hashes an organization email before using it as a Firestore document ID.
function organizationEmailKey(email) {
  return createHash("sha256")
    .update(text(email, 254).toLowerCase())
    .digest("hex");
}

// Reads a trusted role claim and falls back only for standard and safe-contact accounts.
function roleFromUser(user) {
  const claim = user.customClaims?.role;
  if (ROLES.has(claim)) {
    return claim;
  }
  const domain = (user.email || "").split("@")[1] || "";
  if (domain === "safe.olive.invalid") {
    return "SAFE_CONTACT";
  }
  // Privileged roles must always come from server-managed Firebase Auth claims.
  // A profile or email alias is not authorization because a user may edit it.
  return "USER";
}

// Identifies internal Firebase aliases that must not be shown as real contact emails.
function isTemporaryEmail(email) {
  return /@(users|safe|staff|admin)\.olive\.invalid$/i.test(email || "");
}

// Checks the required profile fields for the selected account role.
function profileComplete(role, profile) {
  if (!profile || typeof profile !== "object" || Array.isArray(profile)) {
    return false;
  }
  const hasName = typeof profile.fullName === "string" && Boolean(text(profile.fullName, 120));
  const hasDateOfBirth = typeof profile.dob === "string" && /^\d{4}-\d{2}-\d{2}$/.test(profile.dob);
  if (role === "STAFF" || role === "ADMIN") {
    const hasRealEmail =
      typeof profile.email === "string" && Boolean(text(profile.email, 254)) && !isTemporaryEmail(profile.email);
    return hasName && hasDateOfBirth && hasRealEmail;
  }
  const hasLanguage = typeof profile.language === "string" && Boolean(text(profile.language, 60));
  const hasCity = typeof profile.address?.city === "string" && Boolean(text(profile.address.city, 160));
  return hasName && hasDateOfBirth && hasLanguage && hasCity;
}

// Builds the first stored profile while excluding fields that do not apply to the role.
function defaultProfile(username, role, input, email) {
  const address =
    input.address && typeof input.address === "object" ? input.address : {};
  return {
    username,
    fullName: text(input.fullName, 120),
    affectedNames: role === "USER" ? text(input.affectedNames, 500) : "",
    abuserName: role === "USER" ? text(input.abuserName, 120) : "",
    dob: text(input.dob, 10),
    language:
      role === "USER" || role === "SAFE_CONTACT"
        ? text(input.language, 60) || "English"
        : "English",
    situation: role === "USER" ? text(input.situation, 2000) : "",
    region: text(input.region, 200),
    triggerHash: "",
    contacts: [],
    address: {
      line1: text(address.line1, 160),
      line2: text(address.line2, 160),
      city: text(address.city, 160),
      province: text(address.province, 160),
      postalCode: text(address.postalCode, 160),
      country: text(address.country, 160) || "Canada",
    },
    coverType: "NEWS",
    role,
    email:
      role === "STAFF" || role === "ADMIN"
        ? isTemporaryEmail(email)
          ? ""
          : email
        : "",
    phone: text(input.phone, 40),
  };
}

module.exports = {
  validPassword,
  text,
  normalizedUsername,
  normalizedExistingUsername,
  normalizedRole,
  signInEmail,
  organizationEmailKey,
  roleFromUser,
  isTemporaryEmail,
  profileComplete,
  defaultProfile,
  protectedAccount,
  PROTECTED_USERNAMES,
};
