# Getting started



## Prerequisites

for users:
A compatible android device with Android 17+

for developers:
Android studio Quail

## Install
### For Users
1. Follow [this link]() to download the .apk file on your android device
2. Run the .apk file on your device, you may be prompted to change a setting allowing third party apps to be installed on your phone.
3. Run the new Olive app.

### For Developers

Clone the repository

```bash
git clone https://github.com/macewanCS/F26_D1.git
```



Install dependencies using gradle build/sync in android studio.

## Verify your setup

Run the project's checks before opening a pull request:

```terminal
./gradlew testDebugUnitTest
./gradlew check
./gradlew connectedAndroidTest
```

or if you are on windows

```Shell
.\test-all.bat
```

If a step fails, check that the required runtime is installed and that your environment configuration is complete. See [Configuration](#/configuration) for an example of how to document project settings.

## Run Locally
After the Gradle build has completed and the
## Next steps

Explore the [project structure](#/project-structure), or add your own setup instructions for testing, linting, and contributing.
