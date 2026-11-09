// Creates 360 fictional load-test accounts only after the operator confirms the Firebase project.
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {defaultProfile, organizationEmailKey, signInEmail, validPassword} = require("../src/accountPolicy");
const {accounts, triggerHash, TEST_TRIGGER} = require("./load-test-account-fixtures");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
const password = String(process.env.OLIVE_LOAD_TEST_PASSWORD || "");
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and set OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}
if (!accounts.every(account => validPassword(password, account.username))) {
  throw new Error("OLIVE_LOAD_TEST_PASSWORD must meet the password rules and must not contain a test username.");
}

const bucketName = String(process.env.OLIVE_LOAD_TEST_STORAGE_BUCKET || `${projectId}.firebasestorage.app`).trim();
const app = initializeOliveAdminApp({
  projectId,
  storageBucket: bucketName,
}, "load-test-provisioning");
const auth = getAuth(app);
const firestore = getFirestore(app);
const bucket = getStorage(app).bucket();

// Uploads the same profile JSON format used by the Android and web applications.
async function writeProfile(uid, profile) {
  await bucket.file(`accounts/${uid}/profile.json`).save(JSON.stringify(profile, null, 2), {
    contentType: "application/json",
    resumable: false,
  });
}

// Creates or replaces one trusted Firestore document used by account lookup and staff assignment.
async function writeDocument(collection, documentId, value) {
  await firestore.collection(collection).doc(documentId).set(value);
}

// Creates one account idempotently so rerunning the script repairs incomplete test data.
async function provision(account) {
  const authEmail = signInEmail(account.username, account.role, account.email);
  let user;
  try {
    user = await auth.getUserByEmail(authEmail);
    user = await auth.updateUser(user.uid, {password, displayName: account.username,
      disabled: false, emailVerified: true});
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
    user = await auth.createUser({email: authEmail, password, displayName: account.username,
      disabled: false, emailVerified: true});
  }
  await auth.setCustomUserClaims(user.uid, {role: account.role});

  const profile = defaultProfile(account.username, account.role, account, account.email || authEmail);
  profile.coverType = account.coverType;
  if (account.role === "USER") profile.triggerHash = triggerHash;
  await writeProfile(user.uid, profile);
  await writeDocument("accountDirectory", account.username, {
    uid: user.uid, username: account.username, role: account.role,
    protected: false,
    fullName: profile.fullName,
    contactEmail: profile.email,
    phone: profile.phone,
    profileComplete: true,
    ...(account.role === "STAFF" ? {loginAlias: authEmail} : {}),
    createdAt: new Date(),
  });
  if (account.role === "STAFF") {
    await writeDocument("safetyStaff", user.uid, {
      available: true, activeCount: 0,
    });
    await writeDocument("organizationLoginAliases", organizationEmailKey(account.email), {
      uid: user.uid, username: account.username, role: account.role,
      authEmail, updatedAt: new Date(),
    });
  }
  console.log(`Ready: ${account.username} (${account.role}) — ${account.fullName}, ${account.region}`);
}

// Uses small groups so this larger fixture is practical without flooding Firebase administration APIs.
(async () => {
  for (let index = 0; index < accounts.length; index += 5) {
    await Promise.all(accounts.slice(index, index + 5).map(provision));
  }
  console.log(`Created or repaired ${accounts.length} fictional accounts in ${projectId}.`);
  console.log(`All accounts use OLIVE_LOAD_TEST_PASSWORD. User trigger phrase: ${TEST_TRIGGER}`);
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
