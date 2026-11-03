# Android debug distribution

The download page's button redirects to this Firebase Storage object:

`public/android/olive-branch.apk`

Build the debug APK in Android Studio or with `gradlew :app:assembleDebug`, then
upload `app/build/outputs/apk/debug/app-debug.apk` to this exact Storage path.
Upload a new APK there when you want to replace the current download.

Storage rules must be deployed separately. They permit public downloads only
for the exact APK path and deny client uploads and deletion.

## Debug-build limits

Android debug builds use the debug signing key and Firebase App Check debug
provider. Protected services require the device's debug token to be registered
in Firebase. If a new APK was signed with a different debug key, users must
uninstall the older build before installing it.
