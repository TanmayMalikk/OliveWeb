# Signed APK distribution

Olive is distributed from `https://yourolivebranch.ca/get`, not Google Play. The QR code directly
triggers the download from `https://yourolivebranch.ca/download/android`. Each release uploads an immutable APK and updates
`public/android/latest.json`. After the manifest switches, the publisher removes older release APKs from Firebase Storage,
keeping the latest build and the previous build. The QR code and download button always serve the latest release.

## Local release build setup

- Add the settings from [local.properties.example](../local.properties.example) to the ignored
  root `local.properties`. Keep the Android SDK path if Android Studio already set it.
- Copy [keystore.properties.example](../keystore.properties.example) to the ignored root
  `keystore.properties` and enter the path, alias, and passwords for the existing release keystore.
  Use the same signing key as the published APK so Android can install the update over it.
- The build reads its Firebase client configuration from `app/google-services.json`. The Firebase
  service account is needed only for publishing to Storage.

Use `scripts/Initialize-ReleaseSigning.ps1` only when setting up a new app without an existing
release key. Keep encrypted backups of the release key and its passwords outside the repository.

Olive uses `vMajor.Minor.Revision.Build`. The first deployment version is `v1.0.0.0`:

- `1` is the major version for a large or compatibility-changing release.
- The first `0` is the minor version for a feature release.
- The second `0` is the revision for fixes to that feature release.
- The last `0` is the automatically increasing build number.

For updates, check the [current release manifest](https://yourolivebranch.ca/api/android/releases/latest)
and choose a higher version code. Set the matching version name and code before building.
The four-part version
`v1.0.0.0` maps to `100000000`:

```powershell
$env:OLIVE_VERSION_CODE = "100000000"
$env:OLIVE_VERSION_NAME = "v1.0.0.0"
.\gradlew.bat clean :app:assembleRelease
```

## Publish a local build

Follow [LOCAL-ANDROID-RELEASE.txt](../LOCAL-ANDROID-RELEASE.txt) to build, sign and publish
from PowerShell. Use [release-publish.ps1.example](../release-publish.ps1.example) for Firebase
publishing settings and keep the service account JSON in the ignored private_credentials folder.

## GitHub Actions secrets

Add these repository secrets before running **Publish signed Android release**:

- `OLIVE_RELEASE_KEYSTORE_BASE64`
- `OLIVE_RELEASE_STORE_PASSWORD`
- `OLIVE_RELEASE_KEY_ALIAS`
- `OLIVE_RELEASE_KEY_PASSWORD`
- `FIREBASE_RELEASE_SERVICE_ACCOUNT`
- `GEOAPIFY_API_KEY`
- `MAPBOX_ACCESS_TOKEN`
- `REALTIME_DATABASE_URL`
- `VOIP_API_BASE_URL`

On Windows, create the keystore secret without changing the binary:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\Tanma\Documents\olive-release.jks")) |
  Set-Clipboard
```

The Firebase service account needs permission to list, read, write, and delete release objects in Storage. Storage rules are
deployed separately by a privileged maintainer rather than by the APK publishing job. Paste the
complete JSON as `FIREBASE_RELEASE_SERVICE_ACCOUNT`; never commit it.

## Publishing a release

Open GitHub Actions, choose **Publish signed Android release**, and select **Run workflow**. Keep the
release line at `v1.0.0` for normal builds. The workflow starts its build component at `0` and
increments it automatically. Change only the release line when making a major, minor, or revision
release. You do not edit `versionCode` or `versionName` in Gradle for normal published releases.

Android still requires a final user confirmation for sideloaded updates. Olive automatically checks
the REST manifest on unmetered networks, validates the downloaded file and signing certificate, and
then opens Android's protected package installer.

## Wear OS companion

[LOCAL-WATCH-RELEASE.txt](../LOCAL-WATCH-RELEASE.txt) builds a separate signed watch APK.
Increase its version code for each watch update. It uses the phone release keystore so that
the package name and signing certificate match across devices for Wear OS Data Layer sync.
Keep the watch APK out of the phone's Firebase release feed.

Distribute the watch APK as a separate download from the website. Users install the phone
app on their Android phone and the watch APK directly on their paired Wear OS watch. The
website download provides the APK file; installation on the watch uses ADB over Wi-Fi.

Connect the watch and computer to the same Wi-Fi network. On the watch, enable ADB debugging
and Wireless debugging. Select Pair new device to get the pairing port and code. Then use the
connection port from the main Wireless debugging screen to connect and install:

```powershell
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r .\distribution\OliveFitness-Wear-0.2.0.apk
```

Open Olive Fitness on the watch after installation. Users can turn off Wireless debugging
once the install finishes. For watch updates, use a higher watch version code, the same
signing key, and repeat the install command with the newer watch APK.

## Firebase App Check

Register the release key's SHA-256 fingerprint. Because the APK is distributed only outside Google
Play, configure Play Integrity advanced settings with `PLAY_RECOGNIZED` and `LICENSED` not required,
and require Device Integrity. Release installations then use Play Integrity rather than debug tokens.

Display the fingerprint after creating the key:

```powershell
keytool -list -v -keystore "$env:USERPROFILE\Documents\OliveRelease\olive-release.jks" -alias olive-release
```

## First migration from debug

A release-signed APK cannot update an existing debug-signed installation. Before uninstalling a debug
copy, verify that every encrypted document can be recovered from another authorized device. Removing
the debug app also removes its Android Keystore keys and local-only data.
