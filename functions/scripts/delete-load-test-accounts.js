// Deletes the 53 known load-test accounts through Olive's normal full account-cleanup function.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");

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

const appRoot = path.resolve(__dirname, "../..");
const cliConfigPaths = [
  path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
  process.env.APPDATA && path.join(process.env.APPDATA, "configstore", "firebase-tools.json"),
].filter(Boolean);

// Uses the Firebase CLI session only to find each generated account UID.
function cliToken() {
  const configPath = cliConfigPaths.find(candidate => fs.existsSync(candidate));
  const tokens = configPath && JSON.parse(fs.readFileSync(configPath, "utf8")).tokens;
  if (!tokens?.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    throw new Error(`Firebase CLI login is missing or expired for ${appRoot}. Run: npx firebase-tools projects:list`);
  }
  return tokens;
}

const credential = {getAccessToken: async () => {
  const tokens = cliToken();
  return {access_token: tokens.access_token,
    expires_in: Math.max(60, Math.floor((Number(tokens.expires_at) - Date.now()) / 1000))};
}};
const app = initializeApp({credential, projectId}, "load-test-cleanup");
const auth = getAuth(app);

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
