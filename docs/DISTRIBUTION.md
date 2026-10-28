# Android distribution

The download site uses one stable public Firebase Storage object:

`public/android/olive-branch.apk`

The workflow in `.github/workflows/publish-android-main.yml` builds a debug APK
and replaces that file after every successful push to `main`. Because the QR encodes the stable object
URL, it always downloads the newest published build without needing a newly
generated image or a changed web address.

## Required GitHub Actions secrets

Add these repository secrets before the workflow runs:

- `FIREBASE_SERVICE_ACCOUNT`: a Google service-account JSON document with
  permission to replace objects in
  `olive-21239.firebasestorage.app`.
- `GEOAPIFY_API_KEY`: the production Geoapify browser key used by the Android app.
- `MAPBOX_ACCESS_TOKEN`: the public Mapbox token used only for traffic-aware navigation routes.
- `REALTIME_DATABASE_URL`: the production Firebase Realtime Database HTTPS URL.

The Firebase service account needs Storage Object Admin permission. Keep its JSON
only in repository secrets. Storage rules are deployed separately from this workflow.

## Debug build behavior

CI assigns version code `200000 + GITHUB_RUN_NUMBER`, which lets Android install
each published debug build over the preceding debug build while the cached debug
signing key remains available. If GitHub evicts that cache, the next build gets
a new signing key and requires uninstalling the old debug app. Debug APKs also
cannot update an installed release-signed app; uninstall the release build first.
The object is uploaded with caching disabled
and an APK content type. Client SDKs cannot overwrite or delete it; Firebase
Storage rules allow anonymous reads only for the exact published path. Firebase
App Check uses its debug provider in this build, so the debug token must be
registered in Firebase for protected services to work.

The production URL is committed in `web/.env.download`. To use another bucket or
path, change both that URL and `APK_OBJECT` in the workflow, then update the exact
public path in `firebase/storage.rules`.
