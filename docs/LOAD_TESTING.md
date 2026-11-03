# Load testing accounts and locations

This guide creates fictional accounts and Alberta locations for testing the Admin map and support features. Do not use a real account password.

## 1. Open the project

Run this in PowerShell:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1
```

## 2. Sign in to Firebase

```powershell
npx firebase-tools login --reauth
npx firebase-tools projects:list
```

Make sure `olive-21239` appears in the project list.

## 3. Set the test configuration

These values last only for the current PowerShell window.

```powershell
$env:OLIVE_LOAD_TEST_PROJECT_ID="olive-21239"
$env:OLIVE_LOAD_TEST_CONFIRM_PROJECT="olive-21239"
$env:OLIVE_LOAD_TEST_DATABASE_URL="https://olive-21239-default-rtdb.firebaseio.com"
$env:OLIVE_LOAD_TEST_PASSWORD="YOUR_TEST_PASSWORD"
```

Use quotation marks around the password. Replace `YOUR_TEST_PASSWORD` with the shared password for the fictional accounts.

## 4. Deploy the Admin map permissions

This is needed only when the Realtime Database rules have changed.

```powershell
npx firebase-tools deploy --only database --project olive-21239
```

## 5. Install the Functions packages

```powershell
cd .\functions
npm install
```

## 6. Create the fictional accounts

```powershell
npm run provision-load-test-accounts
```

This creates:

- 40 users named `loaduser01` through `loaduser40`
- 8 staff accounts named `loadstaff01` through `loadstaff08`
- 5 safe-contact accounts named `loadsafe01` through `loadsafe05`

All test profiles use different fictional identities and Alberta locations. The shared trigger is `help`. A backup PIN must still be added from a device because the PIN is encrypted on that device.

## 7. Add the Alberta map locations

Mark 20 users active and 20 users last-known:

```powershell
$env:OLIVE_LOAD_TEST_LOCATIONS_ACTIVE="true"
npm run seed-load-test-locations
```

The command should finish with:

```text
Seeded 40 admin-map locations: 20 active and 20 last-known.
```

## 8. Simulate 10 moving users

```powershell
npm run simulate-load-test-locations
```

Keep this terminal open while testing. The simulator updates the locations every two seconds and moves these users at 60 km/h:

| Account | Alberta location |
| --- | --- |
| `loaduser01` | Edmonton |
| `loaduser02` | Calgary |
| `loaduser03` | Red Deer |
| `loaduser04` | Lethbridge |
| `loaduser05` | Medicine Hat |
| `loaduser06` | Grande Prairie |
| `loaduser07` | Fort McMurray |
| `loaduser08` | Airdrie |
| `loaduser09` | Spruce Grove |
| `loaduser10` | St. Albert |

Each moving user follows a separate loop around the seeded location. Ten other users remain active and stationary. Twenty users remain last-known.

Press `Ctrl+C` to stop the simulator. Their final positions then become last-known locations.

Open a second terminal tab if you need to enter other commands while the simulator is running. In Android Studio, press `Alt+F12` to open the Terminal and use the `+` button to create another terminal tab.

## 9. Install the current Android debug build

Run this from a second PowerShell terminal:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1
.\gradlew.bat :app:installDebug
```

Sign in to an Admin account and open the organization map. Unselected users appear only as dots. Selecting a dot reveals that user's details.

## 10. Run automated tests

Run the Functions tests:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1\functions
npm test
```

Run the project tests without device UI tests:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1
.\test-all.bat
```

Run the complete suite with device UI tests:

```powershell
.\test-all.bat -WithDeviceTests
```

## 11. Reset every location to last-known

Stop the movement simulator with `Ctrl+C`, then run:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1\functions
$env:OLIVE_LOAD_TEST_LOCATIONS_ACTIVE="false"
npm run seed-load-test-locations
```

## 12. Delete the fictional accounts

Do this only after the stress test is finished:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1\functions
npm run delete-load-test-accounts
```

The project ID and confirmation ID must match before any provisioning, location, or cleanup script changes Firebase data.
