// Moves 50 fictional users around repeatable city loops for admin-map stress testing.
const {getAuth} = require("firebase-admin/auth");
const {getDatabase} = require("firebase-admin/database");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");
const {positionAt60Kmph, routeLoop} = require("./load-test-location-simulation");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
const databaseURL = String(process.env.OLIVE_LOAD_TEST_DATABASE_URL || "").trim();
const updateIntervalMilliseconds = 2_000;
const movingUserCount = 50;
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}
if (!/^https:\/\/.+\.firebaseio\.com\/?$/.test(databaseURL) &&
    !/^https:\/\/.+\.firebasedatabase\.app\/?$/.test(databaseURL)) {
  throw new Error("Set OLIVE_LOAD_TEST_DATABASE_URL to this project's Realtime Database URL.");
}

const app = initializeOliveAdminApp({
  projectId,
  databaseURL,
}, "load-test-location-simulation");
const auth = getAuth(app);
const database = getDatabase(app);

// Resolves the first 50 fictional users and gives each one its own two-kilometre route loop.
async function movingUsers() {
  const fixtures = accounts.filter(account => account.role === "USER").slice(0, movingUserCount);
  return Promise.all(fixtures.map(async account => {
    const user = await auth.getUserByEmail(signInEmail(account.username, account.role));
    return {...account, uid: user.uid, route: routeLoop(account.latitude, account.longitude)};
  }));
}

// Updates all 50 positions together so the admin map receives one consistent movement frame.
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
