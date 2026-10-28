# Android release build

The workflow in `.github/workflows/publish-android-main.yml` builds a signed
release APK after each push to `main`. It uploads the APK to the stable public
Firebase Storage path `public/android/olive-branch.apk`, which the website QR
code uses. The `olive-branch-release` GitHub Actions artifact is also available
from a successful run.

## GitHub Actions secrets

Add these repository secrets before running the workflow:

- `ANDROID_KEYSTORE_BASE64`: Base64 encoding of the release keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `GEOAPIFY_API_KEY`: Android address-suggestion key.
- `MAPBOX_ACCESS_TOKEN`: Android navigation token.
- `FIREBASE_SERVICE_ACCOUNT`: service-account JSON with permission to replace
  the APK object in `olive-21239.firebasestorage.app`.

The workflow uses the keystore alias `oliveRelease`, the same password for the
keystore and key, and the project's Realtime Database URL.

The Firebase configuration file `app/google-services.json` is already in the
repository. Keep the keystore and passwords out of Git and back them up securely.
The same signing key must be used for every update.

On Windows, encode the keystore for the first secret with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\olive-release.jks')) |
  Set-Clipboard
```

CI sets the version code to `200000 + GITHUB_RUN_NUMBER` so later builds can
update earlier release builds. An installed debug APK has a different signature
and must be uninstalled before installing this release APK.

## App Check

The release app uses Firebase App Check with Play Integrity. Register the
release certificate SHA-256 in Firebase App Check and link the Firebase project
to the app in Google Play Console. For a directly downloaded APK, configure
the outside-Google-Play verdict settings before relying on App Check-enforced
services. Test sign-in and protected features on a clean phone.
