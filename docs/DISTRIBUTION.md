# Android debug distribution

The download website uses one stable Firebase Storage object:

`public/android/olive-branch.apk`

After each push to `main`, `.github/workflows/publish-android-main.yml` builds
the debug APK and replaces that object. The download server accepts only this
specific object, so the QR code cannot accidentally be redirected to a release
APK. The QR code itself does not need to change.

## Required GitHub Actions secrets

- `FIREBASE_SERVICE_ACCOUNT`: service-account JSON allowed to replace the APK.
- `GEOAPIFY_API_KEY`: Android address-suggestion key.
- `MAPBOX_ACCESS_TOKEN`: Android navigation token.
- `REALTIME_DATABASE_URL`: Firebase Realtime Database HTTPS URL.

Storage rules must be deployed separately. They permit public downloads only
for the exact APK path and deny client uploads and deletion.

## Debug-build limits

The workflow caches one debug signing key so newer CI builds can update older
CI builds. If GitHub removes that cache, users must uninstall the old build
before installing the newly signed one. Firebase App Check also uses the debug
provider, so protected services require the device's debug token to be
registered in Firebase.
