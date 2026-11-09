# Load testing 360 accounts on macOS or Linux

This guide runs the fictional Alberta and British Columbia account, support, friendship, and
location tests from a Bash or Zsh terminal. Do not use a real account password.

## 1. Open the cloned project

Replace the example path with the location of the project on your computer.

```bash
cd ~/path/to/F26_D1
```

## 2. Check the required tools

```bash
node --version
npm --version
java --version
```

The Functions project expects Node.js 22. The Android project also needs the Java version configured for the project in Android Studio.

## 3. Configure Firebase Admin credentials

```bash
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/private_credentials/olive-firebase-admin.json.json"
```

The scripts use Firebase Admin's Application Default Credentials. Keep the service-account file in
the ignored `private_credentials` directory and never commit it.

## 4. Set the test configuration

These values last only for the current terminal window.

```bash
export OLIVE_LOAD_TEST_PROJECT_ID="olive-21239"
export OLIVE_LOAD_TEST_CONFIRM_PROJECT="olive-21239"
export OLIVE_LOAD_TEST_DATABASE_URL="https://olive-21239-default-rtdb.firebaseio.com"
export OLIVE_LOAD_TEST_PASSWORD="demopass1!"
```

## 5. Deploy the Admin map permissions

This is needed only if the Realtime Database rules have changed.

```bash
npx firebase-tools deploy --only database --project olive-21239
```

## 6. Install packages and create the accounts

```bash
cd functions
npm install
npm run provision-load-test-accounts
```

This creates 250 users, 100 safe-contact accounts, and 10 staff accounts. Every profile has a
unique fictional name and a repeatable off-centre point inside an Alberta or British Columbia city.

## 7. Add the Alberta and British Columbia map locations

```bash
export OLIVE_LOAD_TEST_LOCATIONS_ACTIVE="true"
npm run seed-load-test-locations
```

This produces 125 active locations and 125 last-known locations.

## 7a. Add support tickets, safety plans, and friendships

```bash
npm run seed-load-test-support
```

This assigns every user a regular support ticket and a safety-plan consultation using the
least-busy load staff. Exactly 55% of each is completed. Every safe contact receives five accepted
load users as friends.

## 8. Simulate 50 moving users

```bash
npm run simulate-load-test-locations
```

Keep this terminal open. Fifty users move around their seeded city loops at 60 km/h, 75 remain
active and stationary, and 125 remain last-known.

Press `Control+C` to stop the simulation. The moving users' final positions become last-known locations.

Open another terminal tab or window to run other commands while the simulator is active.

## 9. Install the current Android debug build

From another terminal:

```bash
cd ~/path/to/F26_D1
chmod +x gradlew
./gradlew :app:installDebug
```

The computer must have an Android device or emulator connected through ADB. Sign in to an Admin account and open the organization map.

## 10. Run the automated tests

Run the Functions tests:

```bash
cd ~/path/to/F26_D1/functions
npm test
```

Run Android unit tests and build checks:

```bash
cd ~/path/to/F26_D1
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The Windows `test-all.bat` wrapper does not run on Unix. Use the Gradle command above unless a Unix test wrapper is added later.

## 11. Reset every location to last-known

Stop the simulator first, then run:

```bash
cd ~/path/to/F26_D1/functions
export OLIVE_LOAD_TEST_LOCATIONS_ACTIVE="false"
npm run seed-load-test-locations
```

## 12. Delete the fictional accounts

Only run this after testing is complete:

```bash
cd ~/path/to/F26_D1/functions
export OLIVE_LOAD_TEST_DELETE_CONFIRM="DELETE-360-olive-21239"
export OLIVE_LOAD_TEST_FIREBASE_API_KEY="YOUR_FIREBASE_WEB_API_KEY"
export OLIVE_LOAD_TEST_ADMIN_EMAIL="YOUR_PROTECTED_ADMIN_EMAIL"
export OLIVE_LOAD_TEST_ADMIN_PASSWORD="YOUR_ADMIN_PASSWORD"
npm run delete-load-test-accounts
```

The project ID and confirmation ID must match before any provisioning, location, or cleanup script changes Firebase data.
