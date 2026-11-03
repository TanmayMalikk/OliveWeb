// Creates or repairs the four school-demo accounts without storing their password in source control.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {defaultProfile, organizationEmailKey, signInEmail} = require("../src/accountPolicy");

const PROJECT_ID = "olive-21239";
const APP_ROOT = path.resolve(__dirname, "../..");
const cliConfigPaths = [
  path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
  process.env.APPDATA && path.join(process.env.APPDATA, "configstore", "firebase-tools.json"),
].filter(Boolean);

function cliToken() {
  const configPath = cliConfigPaths.find((candidate) => fs.existsSync(candidate));
  const tokens = configPath && JSON.parse(fs.readFileSync(configPath, "utf8")).tokens;
  if (!tokens?.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    throw new Error(`Firebase CLI login is missing or expired for ${APP_ROOT}. Run: npx firebase-tools projects:list`);
  }
  return tokens;
}

const credential = {
  getAccessToken: async () => {
    const tokens = cliToken();
    return {
      access_token: tokens.access_token,
      expires_in: Math.max(60, Math.floor((Number(tokens.expires_at) - Date.now()) / 1000)),
    };
  },
};
const app = initializeApp({
  credential,
  projectId: PROJECT_ID,
  storageBucket: `${PROJECT_ID}.firebasestorage.app`,
}, "demo-provisioning");
const auth = getAuth(app);

async function accessToken() {
  return cliToken().access_token;
}

async function writeProfile(uid, profile) {
  const objectName = encodeURIComponent(`accounts/${uid}/profile.json`);
  const response = await fetch(
      `https://storage.googleapis.com/upload/storage/v1/b/${PROJECT_ID}.firebasestorage.app/o?uploadType=media&name=${objectName}`,
      {
        method: "POST",
        headers: {Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json"},
        body: JSON.stringify(profile, null, 2),
      },
  );
  if (!response.ok) {
    throw new Error(`Profile upload failed: ${response.status} ${await response.text()}`);
  }
}

async function writeFirestoreDocument(collection, documentId, fields) {
  const url = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/${collection}/${documentId}`;
  const response = await fetch(url, {
    method: "PATCH",
    headers: {Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json"},
    body: JSON.stringify({fields}),
  });
  if (!response.ok) {
    throw new Error(`${collection} update failed: ${response.status} ${await response.text()}`);
  }
}

const password = process.env.OLIVE_DEMO_PASSWORD;
if (!password) throw new Error("Set OLIVE_DEMO_PASSWORD before provisioning demo accounts.");

const accounts = [
  {
    username: "demostaff", role: "STAFF", fullName: "Demo Staff",
    email: "demostaff@yourolivebranch.ca", protected: true,
  },
  {
    username: "demoadmin", role: "ADMIN", fullName: "Demo Admin",
    email: "demoadmin@yourolivebranch.ca", protected: true,
  },
  {
    username: "demosafe", role: "SAFE_CONTACT", fullName: "Demo Safe Contact",
    email: "", protected: false,
  },
  {
    username: "demouser", role: "USER", fullName: "Demo User",
    email: "", protected: false,
  },
];

async function writeAccountDirectory(user, account) {
  await writeFirestoreDocument("accountDirectory", account.username, {
    uid: {stringValue: user.uid},
    username: {stringValue: account.username},
    role: {stringValue: account.role},
    protected: {booleanValue: account.protected},
    createdAt: {timestampValue: new Date().toISOString()},
  });
}

async function writeOrganizationLogin(user, account, authEmail) {
  if (!account.email) return;
  await writeFirestoreDocument("organizationLoginAliases", organizationEmailKey(account.email), {
    uid: {stringValue: user.uid},
    username: {stringValue: account.username},
    role: {stringValue: account.role},
    authEmail: {stringValue: authEmail},
    updatedAt: {timestampValue: new Date().toISOString()},
  });
}

async function writeStaffSettings(user, account) {
  if (account.role !== "STAFF") return;
  await writeFirestoreDocument("safetyStaff", user.uid, {
    available: {booleanValue: true},
    activeCount: {integerValue: "0"},
  });
}

async function provision(account) {
  const authEmail = signInEmail(account.username, account.role);
  let user;
  try {
    user = await auth.getUserByEmail(authEmail);
    user = await auth.updateUser(user.uid, {
      password,
      displayName: account.username,
      disabled: false,
      emailVerified: true,
    });
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
    user = await auth.createUser({
      email: authEmail,
      password,
      displayName: account.username,
      disabled: false,
      emailVerified: true,
    });
  }

  const claims = {...user.customClaims, role: account.role};
  if (account.protected) claims.protected = true;
  else delete claims.protected;
  await auth.setCustomUserClaims(user.uid, claims);

  const profile = defaultProfile(account.username, account.role, {
    fullName: account.fullName,
    dob: "2000-01-01",
    language: "English",
    region: "Edmonton, Alberta",
    address: {city: "Edmonton", province: "Alberta", country: "Canada"},
    phone: "",
  }, account.email || authEmail);
  await writeProfile(user.uid, profile);
  await writeAccountDirectory(user, account);
  await writeOrganizationLogin(user, account, authEmail);
  await writeStaffSettings(user, account);
  console.log(`Ready: ${account.username} (${account.role})`);
}

Promise.all(accounts.map(provision)).then(() => process.exit(0)).catch((error) => {
  console.error(error);
  process.exit(1);
});
