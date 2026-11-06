// Creates or repairs the four school-demo accounts without storing their password in source control.
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {defaultProfile, organizationEmailKey, signInEmail} = require("../src/accountPolicy");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const PROJECT_ID = "olive-21239";
const app = initializeOliveAdminApp({
  projectId: PROJECT_ID,
  storageBucket: `${PROJECT_ID}.firebasestorage.app`,
}, "demo-provisioning");
const auth = getAuth(app);
const firestore = getFirestore(app);
const bucket = getStorage(app).bucket();

async function writeProfile(uid, profile) {
  await bucket.file(`accounts/${uid}/profile.json`).save(JSON.stringify(profile, null, 2), {
    contentType: "application/json",
    resumable: false,
  });
}

async function writeFirestoreDocument(collection, documentId, value) {
  await firestore.collection(collection).doc(documentId).set(value);
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
  const authEmail = signInEmail(account.username, account.role, account.email);
  await writeFirestoreDocument("accountDirectory", account.username, {
    uid: user.uid,
    username: account.username,
    role: account.role,
    protected: account.protected,
    ...(account.role === "STAFF" || account.role === "ADMIN" ? {
      loginAlias: authEmail,
    } : {}),
    createdAt: new Date(),
  });
}

async function writeOrganizationLogin(user, account, authEmail) {
  if (!account.email) return;
  await writeFirestoreDocument("organizationLoginAliases", organizationEmailKey(account.email), {
    uid: user.uid,
    username: account.username,
    role: account.role,
    authEmail,
    updatedAt: new Date(),
  });
}

async function writeStaffSettings(user, account) {
  if (account.role !== "STAFF") return;
  await writeFirestoreDocument("safetyStaff", user.uid, {
    available: true,
    activeCount: 0,
  });
}

async function provision(account) {
  const authEmail = signInEmail(account.username, account.role, account.email);
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
