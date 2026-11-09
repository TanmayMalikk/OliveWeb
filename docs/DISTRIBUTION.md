# Signed APK distribution

Olive is distributed from `https://yourolivebranch.ca/download/android`, not Google Play. The QR
code always points to that stable route. Each release uploads an immutable APK and replaces only
`public/android/latest.json`, so the QR image never needs to change.

## One-time local signing setup

Run the setup helper. It creates a long-lived key and the ignored `keystore.properties` file without
putting passwords in PowerShell history:

```powershell
Set-Location C:\Users\Tanma\StudioProjects\F26_D1
.\scripts\Initialize-ReleaseSigning.ps1
```

Keep two encrypted backups of the generated key and password outside this repository. Losing this key
means no later APK can update an installed release.

Olive uses `vMajor.Minor.Revision.Build`. The first deployment version is `v1.0.0.0`:

- `1` is the major version for a large or compatibility-changing release.
- The first `0` is the minor version for a feature release.
- The second `0` is the revision for fixes to that feature release.
- The last `0` is the automatically increasing build number.

Build locally with the matching Android version code. The four-part version
`v1.0.0.0` maps to `100000000`:

```powershell
$env:OLIVE_VERSION_CODE = "100000000"
$env:OLIVE_VERSION_NAME = "v1.0.0.0"
.\gradlew.bat clean :app:assembleRelease
```

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

The Firebase service account needs permission to write Storage objects and deploy Storage rules.
Paste the complete JSON as `FIREBASE_RELEASE_SERVICE_ACCOUNT`; never commit it.

## Publishing a release

Open GitHub Actions, choose **Publish signed Android release**, and select **Run workflow**. Keep the
release line at `v1.0.0` for normal builds. The workflow starts its build component at `0` and
increments it automatically. Change only the release line when making a major, minor, or revision
release. You do not edit `versionCode` or `versionName` in Gradle for normal published releases.

Android still requires a final user confirmation for sideloaded updates. Olive automatically checks
the REST manifest on unmetered networks, validates the downloaded file and signing certificate, and
then opens Android's protected package installer.

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
