/* Publishes one immutable signed APK and atomically moves latest.json to that release. */
const { readFileSync, statSync } = require("node:fs");
const { createHash } = require("node:crypto");
const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getStorage } = require("firebase-admin/storage");
const { getMessaging } = require("firebase-admin/messaging");

const TOPIC_APP_UPDATES = "app_updates";

function buildUpdateNotification({ versionCode, versionName }) {
  return {
    topic: TOPIC_APP_UPDATES,
    data: {
      type: "app_update",
      versionCode: String(versionCode),
      versionName: String(versionName),
    },
    android: {
      priority: "high",
    },
  };
}

function parseManifestConfig(env = process.env) {
  const projectId = env.OLIVE_RELEASE_PROJECT_ID?.trim();
  const confirmedProject = env.OLIVE_RELEASE_CONFIRM_PROJECT?.trim();
  const apkPath = env.OLIVE_RELEASE_APK?.trim();
  const versionCode = Number(env.OLIVE_VERSION_CODE);
  const versionName = env.OLIVE_VERSION_NAME?.trim();
  const bucketName = env.OLIVE_RELEASE_BUCKET?.trim() || `${projectId}.firebasestorage.app`;

  if (!projectId || projectId !== confirmedProject) {
    throw new Error("Set OLIVE_RELEASE_PROJECT_ID and OLIVE_RELEASE_CONFIRM_PROJECT to the same project ID.");
  }
  if (!apkPath || !Number.isSafeInteger(versionCode) || versionCode < 1 || !versionName) {
    throw new Error("Set OLIVE_RELEASE_APK, OLIVE_VERSION_CODE, and OLIVE_VERSION_NAME.");
  }
  if (!/^[0-9A-Za-z][0-9A-Za-z._-]{0,39}$/.test(versionName)) {
    throw new Error("OLIVE_VERSION_NAME may contain only letters, numbers, periods, underscores, and hyphens.");
  }
  const minimumSupportedVersionCode = Number(env.OLIVE_MINIMUM_VERSION_CODE || 1);
  if (!Number.isSafeInteger(minimumSupportedVersionCode) || minimumSupportedVersionCode < 1) {
    throw new Error("OLIVE_MINIMUM_VERSION_CODE must be a positive whole number.");
  }
  return {
    projectId,
    apkPath,
    versionCode,
    versionName,
    bucketName,
    minimumSupportedVersionCode,
    mandatory: env.OLIVE_RELEASE_MANDATORY === "true",
    notes: (env.OLIVE_RELEASE_NOTES || "")
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .slice(0, 20),
  };
}

async function publish(env = process.env) {
  const config = parseManifestConfig(env);
  const {
    projectId,
    apkPath,
    versionCode,
    versionName,
    bucketName,
    minimumSupportedVersionCode,
    mandatory,
    notes,
  } = config;

  const bytes = readFileSync(apkPath);
  const sizeBytes = statSync(apkPath).size;
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const apkFileName = `OliveBranch${versionName}.apk`;
  const objectName = `public/android/releases/${versionCode}/${apkFileName}`;
  const encodedObject = encodeURIComponent(objectName);
  const apkUrl = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${encodedObject}?alt=media`;
  const manifest = {
    packageName: "com.olive.app",
    versionCode,
    versionName,
    minimumSupportedVersionCode,
    apkUrl,
    sha256,
    sizeBytes,
    publishedAt: new Date().toISOString(),
    mandatory,
    releaseNotes: notes,
  };

  const app = initializeApp({ credential: applicationDefault(), projectId, storageBucket: bucketName });
  const bucket = getStorage(app).bucket();
  await bucket.upload(apkPath, {
    destination: objectName,
    resumable: false,
    metadata: {
      contentType: "application/vnd.android.package-archive",
      // Mobile browsers otherwise name the download after the encoded Storage path.
      contentDisposition: `attachment; filename="${apkFileName}"`,
      cacheControl: "public,max-age=31536000,immutable",
      metadata: { versionCode: String(versionCode), versionName, sha256 },
    },
  });
  await bucket.file("public/android/latest.json").save(JSON.stringify(manifest, null, 2), {
    resumable: false,
    contentType: "application/json; charset=utf-8",
    metadata: { cacheControl: "public,max-age=60" },
  });
  console.log(`Published Olive ${versionName} (${versionCode}) with SHA-256 ${sha256}.`);

  try {
    const messaging = getMessaging(app);
    const message = buildUpdateNotification({ versionCode, versionName });
    const messageId = await messaging.send(message);
    console.log(`Dispatched FCM app update notification to topic '${TOPIC_APP_UPDATES}' (${messageId}).`);
  } catch (error) {
    console.warn("FCM app update broadcast failed, continuing with published manifest:", error);
  }
}

if (require.main === module) {
  publish().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}

module.exports = {
  TOPIC_APP_UPDATES,
  buildUpdateNotification,
  parseManifestConfig,
  publish,
};
