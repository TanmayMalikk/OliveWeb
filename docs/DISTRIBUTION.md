# Android distribution

The download site uses one stable public Firebase Storage object:

`public/android/olive-branch.apk`

The workflow in `.github/workflows/publish-android-main.yml` replaces that file
after every successful push to `main`. Because the QR encodes the stable object
URL, it always downloads the newest published build without needing a newly
generated image or a changed web address.

## Required GitHub Actions secrets

Add these repository secrets before the workflow runs:

- `ANDROID_KEYSTORE_BASE64`: the release `.jks` file encoded as one Base64 string.
- `ANDROID_KEYSTORE_PASSWORD`: the release keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.
- `FIREBASE_SERVICE_ACCOUNT`: a Google service-account JSON document with
  permission to deploy Firebase Storage rules and replace objects in
  `olive-21239.firebasestorage.app`.
- `GEOAPIFY_API_KEY`: the production Geoapify browser key used by the Android app.
- `MAPBOX_ACCESS_TOKEN`: the public Mapbox token used only for traffic-aware navigation routes.
- `REALTIME_DATABASE_URL`: the production Firebase Realtime Database HTTPS URL.

On Windows, create the keystore secret with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\olive-release.jks')) |
  Set-Clipboard
```

The Firebase service account needs Firebase Rules Admin and Storage Object Admin
permissions. Keep the JSON and signing material only in repository secrets.

## Release behavior

CI assigns version code `200000 + GITHUB_RUN_NUMBER`, which lets Android install
each published build over the preceding one. The object is uploaded with caching
disabled and an APK content type. Client SDKs cannot overwrite or delete it;
Firebase Storage rules allow anonymous reads only for the exact published path.

The production URL is committed in `web/.env.download`. To use another bucket or
path, change both that URL and `APK_OBJECT` in the workflow, then update the exact
public path in `firebase/storage.rules`.
