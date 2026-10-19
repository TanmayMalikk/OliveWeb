const {HttpsError} = require("firebase-functions/v2/https");
const SECTIONS = ["destination", "travel", "contacts", "essentials", "dependants", "aftercare", "documents", "grab"];
const FIELD_KEYS = ["primary", "backup"].flatMap((plan) => SECTIONS.map((section) => `${plan}_${section}`));
function validFields(defaults, changes) {
  if (!defaults || typeof defaults !== "object" || Array.isArray(defaults) ||
      Object.keys(defaults).some((key) => !FIELD_KEYS.includes(key) || typeof defaults[key] !== "string" || defaults[key].length > 4000) ||
      !Array.isArray(changes) || changes.length > FIELD_KEYS.length) throw new HttpsError("invalid-argument", "Invalid plan fields.");
  const seen = new Set();
  for (const change of changes) {
    if (!change || !FIELD_KEYS.includes(change.key) || seen.has(change.key) || typeof change.value !== "string" || change.value.length > 4000 ||
        !Number.isSafeInteger(change.revision) || change.revision < 0 || typeof change.mutationId !== "string" ||
        !/^[a-zA-Z0-9-]{1,64}$/.test(change.mutationId)) throw new HttpsError("invalid-argument", "Invalid plan change.");
    seen.add(change.key);
  }
}
// Field-level revisions merge independent edits and preserve both versions when the same section changes offline.
function mergeFields(existing, defaults, changes) {
  validFields(defaults, changes);
  const fields = {...existing};
  const conflicts = [];
  for (const change of changes) {
    const old = fields[change.key];
    if (old?.mutationId === change.mutationId) continue; // Retrying an acknowledged operation is idempotent.
    if ((old?.revision || 0) !== change.revision) { conflicts.push(change.key); continue; }
    fields[change.key] = {value: change.value, revision: (old?.revision || 0) + 1, mutationId: change.mutationId};
  }
  for (const [key, value] of Object.entries(defaults)) {
    if (!fields[key]) fields[key] = {value, revision: 1, mutationId: "initial"};
  }
  return {fields, conflicts};
}
function staffEligible(user, profile) {
  return !user.disabled && user.customClaims?.role === "STAFF" && Boolean(profile?.fullName && profile?.email && profile?.dob);
}
function chooseStaff(candidates) {
  return candidates.filter((staff) => staff.available !== false)
      .sort((a, b) => (a.activeCount || 0) - (b.activeCount || 0) || a.uid.localeCompare(b.uid))[0];
}
module.exports = {FIELD_KEYS, mergeFields, validFields, staffEligible, chooseStaff};
