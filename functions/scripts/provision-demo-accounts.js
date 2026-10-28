// Run this trusted deployment script to create or repair the protected demo organization accounts.
const {initializeApp, refreshToken} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const cliAuth = require("../../firebaseAdminClient/node_modules/firebase-tools/lib/auth");
const cliApi = require("../../firebaseAdminClient/node_modules/firebase-tools/lib/api");
const {defaultProfile, signInEmail} = require("../src/accountPolicy");

const cliAccount = cliAuth.getProjectDefaultAccount(require("node:path").resolve(__dirname, "../.."));
if (!cliAccount?.tokens?.refresh_token) throw new Error("Sign in with Firebase CLI before provisioning demo accounts.");
const credential = refreshToken({
  client_id: cliApi.clientId(),
  client_secret: cliApi.clientSecret(),
  refresh_token: cliAccount.tokens.refresh_token,
  type: "authorized_user",
});
const app = initializeApp({credential, projectId: "olive-21239", storageBucket: "olive-21239.firebasestorage.app"}, "demo-provisioning");
const auth = getAuth(app);

async function accessToken() {
  return (await cliAuth.getAccessToken(cliAccount.tokens.refresh_token, [])).access_token;
}

async function writeProfile(uid, profile) {
  const name = encodeURIComponent(`accounts/${uid}/profile.json`);
  const response = await fetch(`https://storage.googleapis.com/upload/storage/v1/b/olive-21239.firebasestorage.app/o?uploadType=media&name=${name}`, {
    method: "POST",
    headers: {Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json"},
    body: JSON.stringify(profile, null, 2),
  });
  if (!response.ok) throw new Error(`Profile upload failed: ${response.status} ${await response.text()}`);
}

async function writeDirectory(user, account) {
  const url = `https://firestore.googleapis.com/v1/projects/olive-21239/databases/(default)/documents/accountDirectory/${account.username}`;
  const response = await fetch(url, {
    method: "PATCH",
    headers: {Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json"},
    body: JSON.stringify({fields: {
      uid: {stringValue: user.uid}, username: {stringValue: account.username},
      role: {stringValue: account.role}, protected: {booleanValue: true},
      createdAt: {timestampValue: new Date().toISOString()},
    }}),
  });
  if (!response.ok) throw new Error(`Directory update failed: ${response.status} ${await response.text()}`);
}

const accounts = [
  {username: "olivestaff", password: process.env.OLIVE_DEMO_STAFF_PASSWORD, role: "STAFF", fullName: "Olive Staff", email: "staff@yourolivebranch.ca"},
  {username: "oliveadmin", password: process.env.OLIVE_DEMO_ADMIN_PASSWORD, role: "ADMIN", fullName: "Olive Admin", email: "admin@yourolivebranch.ca"},
];
if (accounts.some((account) => !account.password)) {
  throw new Error("Set OLIVE_DEMO_STAFF_PASSWORD and OLIVE_DEMO_ADMIN_PASSWORD before provisioning.");
}

async function provision(account) {
  const authEmail = signInEmail(account.username, account.role);
  let user;
  try {
    user = await auth.getUserByEmail(authEmail);
    user = await auth.updateUser(user.uid, {
      password: account.password,
      displayName: account.username,
      disabled: false,
      emailVerified: true,
    });
  } catch (error) {
    if (error.code !== "auth/user-not-found") throw error;
    user = await auth.createUser({
      email: authEmail,
      password: account.password,
      displayName: account.username,
      disabled: false,
      emailVerified: true,
    });
  }
  await auth.setCustomUserClaims(user.uid, {role: account.role, protected: true});
  const profile = defaultProfile(account.username, account.role, {
    fullName: account.fullName,
    dob: "2000-01-01",
    email: account.email,
    phone: "",
  }, account.email);
  await writeProfile(user.uid, profile);
  await writeDirectory(user, account);
  console.log(`Ready: ${account.username} (${account.role})`);
}

Promise.all(accounts.map(provision)).then(() => process.exit(0)).catch((error) => {
  console.error(error);
  process.exit(1);
});
