# Load testing 360 accounts, support workflows, and locations

This guide creates fictional Alberta and British Columbia accounts for testing maps, staff
assignment, safety plans, friendships, and support tickets. Do not use a real account password.

## 1. Open the project

Run this in PowerShell:

```powershell
cd C:\Users\Tanma\StudioProjects\F26_D1
```

## 2. Configure Firebase Admin credentials

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\Users\Tan\private_credentials\olive-firebase-admin.json.json"
```

The scripts use Firebase Admin's Application Default Credentials. Keep the service-account file in
the ignored `private_credentials` directory and never commit it.

## 3. Set the test configuration

These values last only for the current PowerShell window.

```powershell
$env:OLIVE_LOAD_TEST_PROJECT_ID="olive-21239"
$env:OLIVE_LOAD_TEST_CONFIRM_PROJECT="olive-21239"
$env:OLIVE_LOAD_TEST_DATABASE_URL="https://olive-21239-default-rtdb.firebaseio.com"
$env:OLIVE_LOAD_TEST_PASSWORD="demopass1!"
```

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

- 250 users named `loaduser001` through `loaduser250`
- 100 safe-contact accounts named `loadsafe001` through `loadsafe100`
- 10 staff accounts named `loadstaff01` through `loadstaff10`

Every profile has a unique fictional name and a repeatable random point 600–1,900 metres away
from an Alberta or British Columbia city centre. The shared trigger is `help`. A backup PIN must
still be added from a device because the PIN is encrypted on that device.

## 7. Add the Alberta and British Columbia map locations

Mark 125 users active and 125 users last-known:

```powershell
$env:OLIVE_LOAD_TEST_LOCATIONS_ACTIVE="true"
npm run seed-load-test-locations
```

The command should finish with:

```text
Seeded 250 admin-map locations: 125 active and 125 last-known.
```

## 7a. Add staff support and shared safety-plan test data

Create one plan consultation and one regular support-button ticket for every load-test user:

```powershell
npm run seed-load-test-support
```

The script follows the app's least-busy assignment policy across all ten load staff. It marks
exactly 55% of support tickets and 55% of plan consultations complete. Each of the 100 safe
contacts becomes an accepted friend of five load users, producing 500 two-way friendships and
blank direct-message chats. The command preserves existing fixture ticket numbers when rerun.

## 8. Simulate 50 moving users

```powershell
npm run simulate-load-test-locations
```

Keep this terminal open while testing. The simulator updates 50 locations every two seconds and
moves `loaduser001` through `loaduser050` at 60 km/h. The first ten start in these communities:

| Account | Starting location |
| --- | --- |
| `loaduser001` | Edmonton |
| `loaduser002` | Calgary |
| `loaduser003` | Red Deer |
| `loaduser004` | Lethbridge |
| `loaduser005` | Medicine Hat |
| `loaduser006` | Grande Prairie |
| `loaduser007` | Fort McMurray |
| `loaduser008` | Airdrie |
| `loaduser009` | St. Albert |
| `loaduser010` | Spruce Grove |

Each moving user follows a separate loop around its off-centre seeded location. Another 75 users
remain active and stationary. The remaining 125 users remain last-known.

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
$env:OLIVE_LOAD_TEST_DELETE_CONFIRM="DELETE-360-olive-21239"
$env:OLIVE_LOAD_TEST_FIREBASE_API_KEY="YOUR_FIREBASE_WEB_API_KEY"
$env:OLIVE_LOAD_TEST_ADMIN_EMAIL="YOUR_PROTECTED_ADMIN_EMAIL"
$env:OLIVE_LOAD_TEST_ADMIN_PASSWORD="YOUR_ADMIN_PASSWORD"
npm run delete-load-test-accounts
```

The project ID and confirmation ID must match before any provisioning, location, or cleanup script changes Firebase data.
