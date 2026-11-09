// Deletes the 53 known load-test accounts through Olive's normal full account-cleanup function.
const {getAuth} = require("firebase-admin/auth");
const {getDatabase} = require("firebase-admin/database");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_DELETE_CONFIRM || "").trim();
const apiKey = String(process.env.OLIVE_LOAD_TEST_FIREBASE_API_KEY || "").trim();
const adminEmail = String(process.env.OLIVE_LOAD_TEST_ADMIN_EMAIL || "").trim();
const adminPassword = String(process.env.OLIVE_LOAD_TEST_ADMIN_PASSWORD || "");
if (!projectId || confirmation !== `DELETE-53-${projectId}`) {
  throw new Error(`Set OLIVE_LOAD_TEST_DELETE_CONFIRM to DELETE-53-${projectId || "PROJECT_ID"}.`);
}
if (!apiKey || !adminEmail || !adminPassword) {
  throw new Error("Set the Firebase API key and a protected admin email/password for the cleanup call.");
}

const databaseURL = String(process.env.OLIVE_LOAD_TEST_DATABASE_URL || "").trim();
const app = initializeOliveAdminApp({projectId, databaseURL}, "load-test-cleanup");
const auth = getAuth(app);
const database = getDatabase(app);

// Signs in as an administrator so the deployed cleanup function applies every normal deletion rule.
async function adminIdToken() {
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
    method: "POST", headers: {"Content-Type": "application/json"},
    body: JSON.stringify({email: adminEmail, password: adminPassword, returnSecureToken: true}),
  });
  const body = await response.json();
  if (!response.ok || !body.idToken) throw new Error(`Admin sign-in failed: ${JSON.stringify(body)}`);
  return body.idToken;
}

// Calls the same backend path used when an admin deletes one account in the app.
async function deleteAccount(idToken, account) {
  const email = signInEmail(account.username, account.role, account.email);
  let user;
  try {
    user = await auth.getUserByEmail(email);
  } catch (error) {
    if (error.code === "auth/user-not-found") {
      console.log(`Already absent: ${account.username}`);
      return;
    }
    throw error;
  }
  const response = await fetch(
      `https://northamerica-northeast1-${projectId}.cloudfunctions.net/adminDeleteAccount`,
      {method: "POST", headers: {Authorization: `Bearer ${idToken}`, "Content-Type": "application/json"},
        body: JSON.stringify({data: {uid: user.uid}})},
  );
  const body = await response.json();
  if (!response.ok || body.error) throw new Error(`Delete ${account.username} failed: ${JSON.stringify(body)}`);
  await Promise.all([
    database.ref(`adminLiveLocations/${user.uid}`).remove(),
    database.ref(`liveLocations/${user.uid}`).remove(),
    database.ref(`liveLocationTrails/${user.uid}`).remove(),
    database.ref(`users/${user.uid}`).remove(),
  ]);
  console.log(`Deleted: ${account.username}`);
}

(async () => {
  const idToken = await adminIdToken();
  for (const account of accounts) await deleteAccount(idToken, account);
  console.log(`Deleted all generated load-test accounts from ${projectId}.`);
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
