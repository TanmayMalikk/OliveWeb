# Moving Olive into the production GitHub repository

Copy the contents of the `Olive` folder in the production-source ZIP into the root of the production repository.

The Firebase-ready ZIP includes Olive’s Android client configuration at `app/google-services.json`, so Firebase Authentication and Storage are enabled after cloning. This client file contains the project identifiers that are already packaged into the Android app; it is not a Firebase Admin credential.

The ZIP intentionally excludes machine-specific files, generated builds, service-account credentials and release-signing credentials. After cloning the production repository locally:

1. Keep `app/google-services.json` in place for project `olive-21239`.
2. Keep `local.properties` local to each Android Studio installation.
3. Keep the release keystore outside the repository. Copy `keystore.properties.example` to `keystore.properties` locally and add the real signing values.
4. Open the repository root in Android Studio and sync Gradle.
5. Run `./gradlew assembleDebug testDebugUnitTest lintDebug` before merging changes.

For signed release builds in GitHub Actions, recreate `keystore.properties` and the release keystore from encrypted GitHub Actions secrets during the workflow. Never commit service-account keys, signing keystores or signing passwords.

Firebase configuration files included for GitHub are `app/google-services.json`, `.firebaserc`, `firebase.json`, `firebase/storage.rules`, `firebase/firestore.rules`, and the emulator rule tests.
