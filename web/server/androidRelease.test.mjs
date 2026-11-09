// Release server: rejects malformed manifests so the QR and updater cannot be redirected elsewhere.
import { describe, expect, it } from "vitest";
import { validateAndroidRelease } from "./httpApp.mjs";

const valid = {
  packageName: "com.olive.app",
  versionCode: 10001,
  versionName: "1.0.0",
  minimumSupportedVersionCode: 1,
  apkUrl:
    "https://firebasestorage.googleapis.com/v0/b/olive-21239.firebasestorage.app/o/public%2Fandroid%2Freleases%2F10001%2Folive-branch-1.0.0.apk?alt=media",
  sha256: "a".repeat(64),
  sizeBytes: 123456,
  publishedAt: "2026-11-09T00:00:00Z",
  mandatory: false,
  releaseNotes: ["First signed release"],
};

describe("Android release manifest", () => {
  it("accepts the signed APK folder", () => {
    expect(validateAndroidRelease(valid)).toMatchObject({ versionCode: 10001 });
  });

  it("rejects another package or download host", () => {
    expect(
      validateAndroidRelease({ ...valid, packageName: "com.example.fake" }),
    ).toBeNull();
    expect(
      validateAndroidRelease({
        ...valid,
        apkUrl: "https://example.com/fake.apk",
      }),
    ).toBeNull();
  });

  it("rejects invalid checksums and sizes", () => {
    expect(validateAndroidRelease({ ...valid, sha256: "bad" })).toBeNull();
    expect(validateAndroidRelease({ ...valid, sizeBytes: 0 })).toBeNull();
  });

  it("rejects a minimum supported version newer than the published APK", () => {
    expect(
      validateAndroidRelease({ ...valid, minimumSupportedVersionCode: 10002 }),
    ).toBeNull();
  });
});
