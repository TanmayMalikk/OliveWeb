# Project Overview
Olive Branch is an android application running on android 17+ 
its purpose is to help victims plan and escape from Domestic Violence.
This is a student Work in Progress for Grant Macewan University.
Educational purposes only.

---------------------------------------------
# Installation
Currently, there is no way to download the .apk file directly to your android device.
Instead, Clone this GitHub repository "https://github.com/macewanCS/F26_D1.git" and open the repository in Android Studio.
Then run the Gradle build/sync, to compile the application
then you can run the application on an emulated or physical device.
---------------------------------------------
# Testing
## UNIX Based Systems
To do the full test suite.
in the Android Studio terminal
While having an emulator or a physical device connected with USB debugging, running the Olive app.
run the following command
```bash
./test-all.sh
```

if you only want to perform unit tests.
run command
```bash
./gradlew testDebugUnitTest check
./gradlew check
```

## Windows Systems
in the Android Studio terminal
While having an emulator running the Olive app.
run the following command
```bash
./test-all.bat -WithDeviceTests
```

If you only want to perform unit tests.
run command
```bash
./test-all.bat 
```
--------------------------------

# App Overview
## Olive
Olive is the default disguise of Olive Branch.
it appears as a food delivery app ala Uber Eats or Skip the Dishes.
The login method with an account is typing the trigger phrase into the search bar to initiate a secure login.
However, there is a hidden olive in the account setting that double tapping also initiates the secure login.

For users without an account the hidden olive will open the login/create account screen.

## Daily Digest
Daily Digest is an offered choice of disguise.
