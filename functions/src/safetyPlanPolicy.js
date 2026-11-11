// Users and support staff: checks safety plan rules.
const {HttpsError} = require("firebase-functions/v2/https");
const {mergeChecklist} = require("./safetyChecklistMerge");
const SECTIONS = ["destination", "travel", "contacts", "essentials", "dependants", "aftercare", "documents", "grab"];
const FIELD_KEYS = ["primary", "backup"].flatMap((plan) => SECTIONS.map((section) => `${plan}_${section}`));

// Rejects malformed safety-plan defaults and offline changes before they are merged.
function validFields(defaults, changes) {
  if (!defaults || typeof defaults !== "object" || Array.isArray(defaults) ||
      Object.keys(defaults).some((key) => !FIELD_KEYS.includes(key) || typeof defaults[key] !== "string" || defaults[key].length > 4000) ||
      !Array.isArray(changes) || changes.length > FIELD_KEYS.length) throw new HttpsError("invalid-argument", "Invalid plan fields.");
  const seen = new Set();
  // Validates each section change and prevents duplicate keys in the same request.
  for (const change of changes) {
    if (!change || !FIELD_KEYS.includes(change.key) || seen.has(change.key) || typeof change.value !== "string" || change.value.length > 4000 ||
        !Number.isSafeInteger(change.revision) || change.revision < 0 || typeof change.mutationId !== "string" ||
        !/^[a-zA-Z0-9-]{1,64}$/.test(change.mutationId) ||
        (change.baseValue != null && (typeof change.baseValue !== "string" || change.baseValue.length > 4000))) {
      throw new HttpsError("invalid-argument", "Invalid plan change.");
    }
    seen.add(change.key);
  }
}
// Field-level revisions merge independent edits and preserve both versions when the same section changes offline.
function mergeFields(existing, defaults, changes) {
  validFields(defaults, changes);
  const fields = {...existing};
  const conflicts = [];
  const conflictValues = {};
  // Applies changes only when their revision still matches the stored section.
  for (const change of changes) {
    const old = fields[change.key];
    if (old?.mutationId === change.mutationId) continue; // Retrying an acknowledged operation is idempotent.
    if ((old?.revision || 0) !== change.revision) {
      const merged = mergeChecklist(change.baseValue, change.value, old?.value);
      if (!merged) {
        conflicts.push(change.key);
        conflictValues[change.key] = change.value;
        continue;
      }
      if (merged.conflicted) {
        conflicts.push(change.key);
        conflictValues[change.key] = merged.localValue;
      }
      if (!merged.conflicted || merged.remoteValue !== old.value) {
        fields[change.key] = {value: merged.remoteValue, revision: old.revision + 1,
          mutationId: merged.conflicted ? (old.mutationId || "merged") : change.mutationId};
      }
      continue;
    }
    fields[change.key] = {value: change.value, revision: (old?.revision || 0) + 1, mutationId: change.mutationId};
  }
  // Adds starter content to new plans and to untouched blank plans created by older app versions.
  for (const [key, value] of Object.entries(defaults)) {
    if (!fields[key]) {
      fields[key] = {value, revision: 1, mutationId: "initial"};
      continue;
    }
    const old = fields[key];
    if (old?.revision === 1 && old?.mutationId === "initial" && old?.value === "" && value !== "") {
      fields[key] = {value, revision: 1, mutationId: "initial"};
    }
  }
  return {fields, conflicts, conflictValues};
}

// Checks whether a staff account is active and has completed its required profile.
function staffEligible(user, profile) {
  return !user.disabled && user.customClaims?.role === "STAFF" && Boolean(profile?.fullName && profile?.email && profile?.dob);
}

// Assigns the available staff member with the fewest users, breaking ties at random.
function chooseStaff(candidates, random = Math.random) {
  const available = candidates.filter((staff) => staff.available !== false);
  if (!available.length) return undefined;
  const leastAssignments = Math.min(...available.map((staff) => staff.activeCount || 0));
  const tied = available.filter((staff) => (staff.activeCount || 0) === leastAssignments);
  return tied[Math.floor(random() * tied.length)];
}
module.exports = {FIELD_KEYS, mergeFields, validFields, staffEligible, chooseStaff};
