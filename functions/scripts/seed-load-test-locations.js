// Places the 40 fictional user accounts at Alberta community-centre coordinates for admin-map testing.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getDatabase} = require("firebase-admin/database");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
const databaseURL = String(process.env.OLIVE_LOAD_TEST_DATABASE_URL || "").trim();
const activeMode = String(process.env.OLIVE_LOAD_TEST_LOCATIONS_ACTIVE || "false").toLowerCase() === "true";
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}
if (!/^https:\/\/.+\.firebaseio\.com\/?$/.test(databaseURL) &&
    !/^https:\/\/.+\.firebasedatabase\.app\/?$/.test(databaseURL)) {
  throw new Error("Set OLIVE_LOAD_TEST_DATABASE_URL to this project's Realtime Database URL.");
}

const appRoot = path.resolve(__dirname, "../..");
const cliConfigPaths = [
  path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
  process.env.APPDATA && path.join(process.env.APPDATA, "configstore", "firebase-tools.json"),
].filter(Boolean);

// Reuses the local Firebase CLI login and does not store an administrator key in the project.
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
const app = initializeApp({credential, projectId, databaseURL}, "load-test-location-seeding");
const auth = getAuth(app);
const database = getDatabase(app);

// Writes every generated user to the map. Active mode marks the first half live and the rest last-known.
(async () => {
  const users = accounts.filter(account => account.role === "USER");
  const activeCount = activeMode ? Math.floor(users.length / 2) : 0;
  for (const [index, account] of users.entries()) {
    const active = index < activeCount;
    const user = await auth.getUserByEmail(signInEmail(account.username, account.role));
    await database.ref(`adminLiveLocations/${user.uid}`).set({
      name: account.fullName,
      latitude: account.latitude,
      longitude: account.longitude,
      accuracy: 25,
      updatedAt: Date.now(),
      active,
    });
    console.log(`Placed ${account.username} [${user.uid}] at ${account.region}${active ? " (active test point)" : " (last known)"}.`);
  }
  console.log(
      `Seeded ${users.length} admin-map locations: ${activeCount} active and ` +
      `${users.length - activeCount} last-known. These are static points, not simulated moving devices.`,
  );
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
