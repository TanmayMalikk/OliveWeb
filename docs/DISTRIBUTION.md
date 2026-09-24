# Direct APK distribution

Olive is installed from a signed APK. A QR code will point to an HTTPS download page once a host is chosen. No hosting or publication has been performed.

## Signing setup

1. In Android Studio, choose Build > Generate Signed App Bundle or APK > APK.
2. Create a release keystore outside the repository. Keep the keystore, alias and passwords securely backed up: future updates must use the same signing key.
3. Copy keystore.properties.example to keystore.properties and fill in those credentials. This file and keystores are Git-ignored.
4. Keep app/google-services.json for olive-21239 in place.
5. Run ./gradlew assembleRelease. The build refuses missing Firebase configuration, local-test mode, missing signing credentials or a missing keystore.

Never publish a debug or local-test APK. A release cannot update a debug installation because they use different signing keys; use a separate test device or preserve needed data before switching installations.

## Package

Run scripts/Package-Release.ps1 from PowerShell after configuring signing. The script builds and checks the APK signature, copies it into distribution/ and writes its SHA-256 checksum. That folder is ignored by Git. It does not upload anything.

For each update, increase versionCode in app/build.gradle.kts. Test the signed build on Pixel 9a and Pixel 10, including returning-user login and profile restoration.

## Hosting and QR

Choose an HTTPS download page you control. Publish the signed APK and checksum, then use the stable page URL for a QR code so the printed code can survive app updates. The page should show the current version and installation instructions. Android requires the user to allow APK installation from their browser.

A host/download URL is still needed before a working QR code can be produced. Physical-device validation and release signing credentials are also outstanding.
