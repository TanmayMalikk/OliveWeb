// Creates 53 fictional load-test accounts only after the operator confirms the Firebase project.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {defaultProfile, organizationEmailKey, signInEmail, validPassword} = require("../src/accountPolicy");
const {accounts, triggerHash, TEST_TRIGGER} = require("./load-test-account-fixtures");

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
const appRoot = path.resolve(__dirname, "../..");
const cliConfigPaths = [
  path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
  process.env.APPDATA && path.join(process.env.APPDATA, "configstore", "firebase-tools.json"),
].filter(Boolean);

// Reuses the developer's Firebase CLI session without adding a service-account key to the repository.
function cliToken() {
  const configPath = cliConfigPaths.find(candidate => fs.existsSync(candidate));
  const tokens = configPath && JSON.parse(fs.readFileSync(configPath, "utf8")).tokens;
  if (!tokens?.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    throw new Error(`Firebase CLI login is missing or expired for ${appRoot}. Run: npx firebase-tools projects:list`);
  }
  return tokens;
}

const credential = {
  getAccessToken: async () => {
    const tokens = cliToken();
    return {access_token: tokens.access_token,
      expires_in: Math.max(60, Math.floor((Number(tokens.expires_at) - Date.now()) / 1000))};
  },
};
const app = initializeApp({credential, projectId, storageBucket: bucketName}, "load-test-provisioning");
const auth = getAuth(app);

async function token() { return cliToken().access_token; }

// Uploads the same profile JSON format used by the Android and web applications.
async function writeProfile(uid, profile) {
  const objectName = encodeURIComponent(`accounts/${uid}/profile.json`);
  const response = await fetch(
      `https://storage.googleapis.com/upload/storage/v1/b/${bucketName}/o?uploadType=media&name=${objectName}`,
      {method: "POST", headers: {Authorization: `Bearer ${await token()}`, "Content-Type": "application/json"},
        body: JSON.stringify(profile, null, 2)},
  );
  if (!response.ok) throw new Error(`Profile upload failed: ${response.status} ${await response.text()}`);
}

// Creates or replaces one trusted Firestore document used by account lookup and staff assignment.
async function writeDocument(collection, documentId, fields) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${collection}/${documentId}`;
  const response = await fetch(url, {method: "PATCH",
    headers: {Authorization: `Bearer ${await token()}`, "Content-Type": "application/json"},
    body: JSON.stringify({fields})});
  if (!response.ok) throw new Error(`${collection} update failed: ${response.status} ${await response.text()}`);
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
    uid: {stringValue: user.uid}, username: {stringValue: account.username}, role: {stringValue: account.role},
    protected: {booleanValue: false},
    ...(account.role === "STAFF" ? {loginAlias: {stringValue: authEmail}} : {}),
    createdAt: {timestampValue: new Date().toISOString()},
  });
  if (account.role === "STAFF") {
    await writeDocument("safetyStaff", user.uid, {
      available: {booleanValue: true}, activeCount: {integerValue: "0"},
    });
    await writeDocument("organizationLoginAliases", organizationEmailKey(account.email), {
      uid: {stringValue: user.uid}, username: {stringValue: account.username}, role: {stringValue: account.role},
      authEmail: {stringValue: authEmail}, updatedAt: {timestampValue: new Date().toISOString()},
    });
  }
  console.log(`Ready: ${account.username} (${account.role}) — ${account.fullName}, ${account.region}`);
}

// Runs sequentially to avoid flooding Auth, Firestore, and Storage administration APIs.
(async () => {
  for (const account of accounts) await provision(account);
  console.log(`Created or repaired ${accounts.length} fictional accounts in ${projectId}.`);
  console.log(`All accounts use OLIVE_LOAD_TEST_PASSWORD. User trigger phrase: ${TEST_TRIGGER}`);
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
