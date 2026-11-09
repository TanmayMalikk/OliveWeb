// Copies existing protected profile summaries into Firestore for the fast admin account list.
const {getFirestore} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {profileComplete} = require("../src/accountPolicy");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_ACCOUNT_BACKFILL_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_ACCOUNT_BACKFILL_CONFIRM_PROJECT || "").trim();
if (!projectId || confirmation !== projectId) {
  throw new Error(
      "Set OLIVE_ACCOUNT_BACKFILL_PROJECT_ID and OLIVE_ACCOUNT_BACKFILL_CONFIRM_PROJECT to the same project ID.",
  );
}

const bucketName = String(
    process.env.OLIVE_ACCOUNT_BACKFILL_STORAGE_BUCKET || `${projectId}.firebasestorage.app`,
).trim();
const app = initializeOliveAdminApp({projectId, storageBucket: bucketName}, "account-directory-backfill");
const firestore = getFirestore(app);
const bucket = getStorage(app).bucket();

// Reads one private profile without exposing its contents in console output.
async function readProfile(uid) {
  try {
    const [contents] = await bucket.file(`accounts/${uid}/profile.json`).download();
    return JSON.parse(contents.toString("utf8"));
  } catch (error) {
    if (error.code === 404) return null;
    throw error;
  }
}

// Repairs one directory row using the profile that already belongs to that account.
async function summaryUpdate(document) {
  const directory = document.data();
  const uid = typeof directory.uid === "string" ? directory.uid : "";
  const role = typeof directory.role === "string" ? directory.role : "";
  if (!uid || !role) return {document, update: null, missingProfile: false};
  const profile = await readProfile(uid);
  if (!profile) {
    return {document, update: {profileComplete: false}, missingProfile: true};
  }
  return {
    document,
    update: {
      fullName: typeof profile.fullName === "string" ? profile.fullName.slice(0, 120) : "",
      contactEmail: typeof profile.email === "string" ? profile.email.slice(0, 254) : "",
      phone: typeof profile.phone === "string" ? profile.phone.slice(0, 40) : "",
      profileComplete: profileComplete(role, profile),
    },
    missingProfile: false,
  };
}

// Processes small groups so Cloud Storage and Firestore are not flooded by the migration.
(async () => {
  const snapshot = await firestore.collection("accountDirectory").get();
  let updated = 0;
  let missingProfiles = 0;
  for (let start = 0; start < snapshot.docs.length; start += 4) {
    const rows = await Promise.all(snapshot.docs.slice(start, start + 4).map(summaryUpdate));
    const batch = firestore.batch();
    rows.forEach(row => {
      if (!row.update) return;
      batch.set(row.document.ref, row.update, {merge: true});
      updated += 1;
      if (row.missingProfile) missingProfiles += 1;
    });
    await batch.commit();
  }
  console.log(`Backfilled ${updated} account summaries in ${projectId}.`);
  console.log(`${missingProfiles} accounts have no stored profile and correctly remain marked as setup required.`);
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
