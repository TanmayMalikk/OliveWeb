// Users and support staff: checks who can read or edit a safety plan.
const {HttpsError} = require("firebase-functions/v2/https");
const {auth, db} = require("./firebaseAdminClient");
const {text, roleFromUser} = require("./accountPolicy");

// Authenticates a safety-plan caller and returns only server-trusted identity details.
async function safetyCaller(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const user = await auth.getUser(request.auth.uid);
  if (user.disabled) throw new HttpsError("permission-denied", "Account disabled.");
  // Organization privileges come from Admin-issued Auth claims, never from an editable profile or request body.
  const role = roleFromUser(user);
  if (!["USER", "SAFE_CONTACT", "STAFF", "ADMIN"].includes(role)) throw new HttpsError("permission-denied", "Safety plan access is unavailable for this role.");
  return {uid: user.uid, role, name: text(user.displayName, 120) || ""};
}

// Validates an owner ID before creating its Firestore safety-plan reference.
function planReference(ownerUid) {
  if (typeof ownerUid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(ownerUid)) throw new HttpsError("invalid-argument", "Invalid owner.");
  return db.collection("safetyPlans").doc(ownerUid);
}

// Allows owners, assigned consulting staff, and admins to edit a safety plan.
function canEdit(caller, plan, ownerUid) {
  return (caller.role === "USER" && caller.uid === ownerUid) ||
    (caller.role === "STAFF" && plan?.staffUid === caller.uid && plan?.consultationRequested === true) ||
    caller.role === "ADMIN";
}

// Adds approved safe contacts to the roles that may read a safety plan.
function canRead(caller, plan, ownerUid) {
  return canEdit(caller, plan, ownerUid) ||
    (caller.role === "SAFE_CONTACT" && Array.isArray(plan?.safeContactUids) && plan.safeContactUids.includes(caller.uid));
}
module.exports = {safetyCaller, planReference, canEdit, canRead};
