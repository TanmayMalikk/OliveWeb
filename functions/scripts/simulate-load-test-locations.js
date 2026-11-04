// Moves 10 fictional users around repeatable Alberta loops for admin-map stress testing.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getDatabase} = require("firebase-admin/database");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");
const {positionAt60Kmph, routeLoop} = require("./load-test-location-simulation");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
const databaseURL = String(process.env.OLIVE_LOAD_TEST_DATABASE_URL || "").trim();
const updateIntervalMilliseconds = 2_000;
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

// Uses the existing Firebase CLI login so no administrator key is saved in the repository.
function cliToken() {
  const configPath = cliConfigPaths.find(candidate => fs.existsSync(candidate));
  const tokens = configPath && JSON.parse(fs.readFileSync(configPath, "utf8")).tokens;
  if (!tokens?.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    throw new Error(`Firebase CLI login is missing or expired for ${appRoot}. Run: npx firebase-tools login --reauth`);
  }
  return tokens;
}

const credential = {getAccessToken: async () => {
  const tokens = cliToken();
  return {
    access_token: tokens.access_token,
    expires_in: Math.max(60, Math.floor((Number(tokens.expires_at) - Date.now()) / 1_000)),
  };
}};
const app = initializeApp({credential, projectId, databaseURL}, "load-test-location-simulation");
const auth = getAuth(app);
const database = getDatabase(app);

// Resolves the first 10 fictional users and gives each one its own two-kilometre route loop.
async function movingUsers() {
  const fixtures = accounts.filter(account => account.role === "USER").slice(0, 10);
  return Promise.all(fixtures.map(async account => {
    const user = await auth.getUserByEmail(signInEmail(account.username, account.role));
    return {...account, uid: user.uid, route: routeLoop(account.latitude, account.longitude)};
  }));
}

// Updates all 10 positions together so the admin map receives one consistent movement frame.
(async () => {
  const users = await movingUsers();
  const startedAt = Date.now();
  let writing = false;
  let stopped = false;

  const writeFrame = async () => {
    if (writing || stopped) return;
    writing = true;
    try {
      const now = Date.now();
      const updates = {};
      users.forEach(user => {
        const position = positionAt60Kmph(user.route, now - startedAt);
        updates[user.uid] = {
          name: user.fullName,
          latitude: position.latitude,
          longitude: position.longitude,
          accuracy: 25,
          updatedAt: now,
          active: true,
        };
      });
      await database.ref("adminLiveLocations").update(updates);
      process.stdout.write(`\rMoving ${users.length} users at 60 km/h · ${new Date(now).toLocaleTimeString()}   `);
    } finally {
      writing = false;
    }
  };

  await writeFrame();
  const timer = setInterval(() => void writeFrame().catch(error => console.error("\nMovement update failed:", error)),
      updateIntervalMilliseconds);

  // Ctrl+C stops the simulator and leaves the final positions as last-known points.
  const stop = async () => {
    if (stopped) return;
    stopped = true;
    clearInterval(timer);
    while (writing) await new Promise(resolve => setTimeout(resolve, 50));
    const now = Date.now();
    await database.ref("adminLiveLocations").update(Object.fromEntries(
        users.map(user => [`${user.uid}/active`, false]),
    ));
    console.log(`\nStopped movement. The ${users.length} final positions are now last-known locations at ${new Date(now).toLocaleTimeString()}.`);
    process.exit(0);
  };
  process.once("SIGINT", () => void stop());
  process.once("SIGTERM", () => void stop());
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
