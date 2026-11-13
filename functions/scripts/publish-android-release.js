/* Publishes one immutable signed APK and atomically moves latest.json to that release. */
const { readFileSync, statSync } = require("node:fs");
const { createHash } = require("node:crypto");
const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getStorage } = require("firebase-admin/storage");
const { getMessaging } = require("firebase-admin/messaging");

const TOPIC_APP_UPDATES = "app_updates";
const RELEASE_PREFIX = "public/android/releases/";
const MANIFEST_PATH = "public/android/latest.json";

// The manifest's APK is authoritative. Keep the highest version below it as a fallback.
async function pruneOldReleases(bucket, manifest) {
  const latestPath = decodeURIComponent(new URL(manifest.apkUrl).pathname.split("/o/")[1] || "");
  const [files] = await bucket.getFiles({ prefix: RELEASE_PREFIX });
  const releases = files.flatMap(file => {
    const match = /^public\/android\/releases\/([1-9]\d*)\/(?:OliveBranch|olive-branch-)[0-9A-Za-z][0-9A-Za-z._-]{0,39}\.apk$/.exec(file.name);
    return match && Number.isSafeInteger(Number(match[1])) ? [{ file, versionCode: Number(match[1]) }] : [];
  });
  if (!releases.some(({file, versionCode}) => file.name === latestPath && versionCode === manifest.versionCode)) {
    throw new Error("Cannot prune releases: latest.json does not point to an existing release APK.");
  }
  const older = releases.filter(release => release.versionCode < manifest.versionCode)
    .sort((a, b) => b.versionCode - a.versionCode || a.file.name.localeCompare(b.file.name));
  const obsolete = [...older.slice(1), ...releases.filter(release =>
    release.versionCode === manifest.versionCode && release.file.name !== latestPath)];
  for (const {file} of obsolete) {
    await file.delete({ ignoreNotFound: true, ifGenerationMatch: file.metadata.generation });
  }
  return obsolete.length;
}

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
  const objectName = `${RELEASE_PREFIX}${versionCode}/${apkFileName}`;
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
  const manifestFile = bucket.file(MANIFEST_PATH);
  const currentMetadata = await manifestFile.getMetadata()
    .then(([metadata]) => metadata)
    .catch(error => { if (error.code !== 404) throw error; });
  const current = currentMetadata && await bucket.file(MANIFEST_PATH, { generation: currentMetadata.generation })
    .download().then(([contents]) => JSON.parse(contents.toString()));
  if (current && (versionCode < current.versionCode ||
      (versionCode === current.versionCode && (current.apkUrl !== apkUrl || current.sha256 !== sha256)))) {
    throw new Error("A release must be newer than the published build. A retry must use the same APK.");
  }
  await bucket.upload(apkPath, {
    destination: objectName,
    resumable: false,
    preconditionOpts: { ifGenerationMatch: 0 },
    metadata: {
      contentType: "application/vnd.android.package-archive",
      // Mobile browsers otherwise name the download after the encoded Storage path.
      contentDisposition: `attachment; filename="${apkFileName}"`,
      cacheControl: "public,max-age=31536000,immutable",
      metadata: { versionCode: String(versionCode), versionName, sha256 },
    },
  }).catch(async error => {
    if (error.code !== 412) throw error;
    const [existing] = await bucket.file(objectName).getMetadata();
    if (existing.metadata?.sha256 !== sha256) throw error;
  });
  await manifestFile.save(JSON.stringify(manifest, null, 2), {
    resumable: false,
    preconditionOpts: { ifGenerationMatch: currentMetadata?.generation ?? 0 },
    contentType: "application/json; charset=utf-8",
    metadata: { cacheControl: "public,max-age=60" },
  });
  const pruned = await pruneOldReleases(bucket, manifest);
  console.log(`Published Olive ${versionName} (${versionCode}) with SHA-256 ${sha256}.`);
  console.log(`Removed ${pruned} obsolete Android release APK(s).`);

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
  pruneOldReleases,
  publish,
};
