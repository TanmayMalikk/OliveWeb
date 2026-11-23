# Olive Branch

> A discreet Android safety app (with a companion web portal and Wear OS app) that helps people plan for and escape domestic violence. The app hides behind an ordinary-looking cover app and opens a private workspace only after a secret entry step.

**Status:** student Work in Progress for MacEwan University. **Educational purposes only.** It is not an emergency service and has not been independently security-audited. If you are in immediate danger, call your local emergency number.

Supports Android 9 (API 28) and newer. Also includes a web portal (`web/`), a Wear OS module (`wear/`), and a Firebase backend (`firebase/`, `functions/`).

## Table of contents

- [How it works](#how-it-works)
  - [User](#user)
  - [Safe Contact](#safe-contact)
  - [Staff](#staff)
  - [Administrator](#administrator)
- [Features available to every account](#features-available-to-every-account)
- [Cover apps](#cover-apps)
- [Web portal](#web-portal)
- [Wear OS companion](#wear-os-companion)
- [Privacy and security](#privacy-and-security)
- [Languages and accessibility](#languages-and-accessibility)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Testing](#testing)
- [Backend and deployment](#backend-and-deployment)
- [Known limitations](#known-limitations)

## How it works

1. A person installs Olive Branch. Its launcher name, icon, and screen look like an ordinary **Daily Digest** (news) or **Fitness Tracker**.
2. They enter a secret **trigger phrase** (or use a hidden icon gesture, depending on the cover) to reach the private workspace, then pass an **app PIN** or **Android device-lock** check.
3. Inside, they can plan, document, message trusted people, and share their location. **Quick Exit** instantly returns to the cover. Backgrounding the app and inactivity also lock it.
4. Staff and administrators use a separate organization workspace ("Olive Tree") to support users who ask for help.

There are four account roles: **User**, **Safe Contact**, **Staff**, and **Administrator**. Staff and administrator accounts are created by an administrator; Users and Safe Contacts register themselves.

### User

The primary role. Users get the full private workspace behind the cover app.

**Cover and entry**
- Choose one of two covers (Daily Digest, Fitness Tracker); the Android launcher name and icon follow the selected cover.
- Configurable secret trigger phrase, plus hidden-icon/gesture entry where the cover supports it.
- Entry by app PIN (4-6 digits) or Android device lock (biometric/device credential), with the app PIN kept as a backup. Failed PIN attempts are counted; five failures trigger Quick Exit.
- Web portal entry via personal private-entry links (with link rotation) and an enrolled 4-6 digit browser PIN.
- **Quick Exit** from any screen, automatic lock when the app or tab is backgrounded, and a configurable idle-lock timer with a warning countdown.

**Account, profile, and privacy settings**
- Self-registration, sign-in, and multi-step onboarding (full name, optional affected persons' names, optional abuser name, date of birth, optional email with explicit communication consent, situation summary, city/address with autocomplete, trigger phrase, safe contacts, cover choice, and safety intake questionnaire).
- Edit profile, change account password (enforcing username exclusion and complexity rules), change entry PIN, change trigger phrase, and switch cover app.
- **Passkey Account Recovery Key** management: enroll passkeys (synced or device-bound), view enrolled recovery credentials, and remove credentials (with a last-credential safeguard) so encrypted documents and messages can be recovered on a new device.
- Toggle recent-file local caching for faster document reopening.
- Optional screen-capture protection setting (see [Privacy and security](#privacy-and-security)).

**Safety planning**
- Personalized safety plan seeded from the onboarding intake questionnaire, with editable primary and backup sections, checklists, and notes.
- Saved locally and queued for sync when offline, with field-level conflict resolution.
- Share the safety plan with selected accepted contacts, with server-side access checks and one-tap revocation.
- Request a **safety-plan consultation** with organization staff.

**Notes and incidents**
- Searchable Keep-style board for private **Notes** and **Incident** records, with kind filtering (All, Notes, Incidents) and newest/oldest sorting.
- Incident records capture date, time, location (via interactive map picker, current GPS lookup, or address autocomplete), narrative details, and encrypted file attachments (including uploading and attaching in a single step).
- Pin/unpin entries, share individual notes or incidents with accepted contacts, move to trash with undo, restore or permanently delete from trash, and inspect per-entry edit history logs.

**Documents, camera, and OCR**
- Upload, list, view (built-in viewer for images, PDFs, video, and audio), download, delete, and share encrypted documents with selected accepted contacts.
- In-app camera capture (photo/video) and attachment picker.
- On-device OCR (`ppocr-sdk`) to extract text from captured images with a review step before saving.
- Client-side encryption before upload. Voice messages are sent only in chat and are excluded from document storage.

**Messaging, calls, and connections**
- Search accounts, send/accept/decline friend requests, and manage an accepted friend list.
- Generate and redeem one-time friend invite codes and shareable links (48-hour validity), usable during onboarding or from the workspace.
- Encrypted direct and group chats, in-chat message search, unsend own messages, conversation archiving, and private notes per contact.
- Record and send encrypted voice messages directly in chat, and place encrypted VoIP voice calls.
- Share documents, notes, incidents, safety plans, and locations directly into a conversation, and inspect all shared items and membership history from the **Chat Info** dialog.
- Add accepted friends into group chats or open support tickets.
- Request live **Staff help** from the dashboard (with assignment status and cancellation) or escalate an existing conversation into a staff-supported chat.

**Location**
- Interactive device location map, live-location sharing with selected contacts (add/remove recipients, stop sharing anytime), map view of locations shared with you, saved location directory, and turn-by-turn style route navigation with manual or automatic refresh.

**Support resources and emergency report**
- Bundled Canadian hotline and support-resource directory with province/territory and city filtering, direct phone dialing, saved/bookmarked hotlines, and an explicit one-time GPS location lookup that does not overwrite the saved profile address.
- Trusted safe-contact directory with relationship details, contact info, a "safe to stay" indicator, and linking to accepted chat friends.
- **Emergency** action that generates an encrypted emergency report PDF (including profile context and current location), uploads it, and alerts organization administrators.

**Activity history**
- Private audit log of settings changes, trigger events, PIN attempts, and privacy events, with text search and date/time filters.

**Fitness cover extras** (when the Fitness Tracker cover is selected)
- Fitness profile and goals (calorie/macro calculator), food and meal logging, barcode/manual nutrition tracking, workout tracking (including outdoor GPS workouts), vitals, history charts, Health Connect and Wear OS watch sync, and an AI fitness coach chat. See [Cover apps](#cover-apps).

### Safe Contact

A trusted person who supports a User. Safe Contacts use a connection-focused workspace without a cover app or app PIN on Android (web accounts still support optional personal entry links and browser PINs).

- **Account and onboarding:** self-register and sign in with the **Safe Contact** account type; complete a short profile (full name, date of birth, optional email with consent, and city/region).
- **Connections and invites:** search users, send/accept/decline friend requests, and generate or redeem 48-hour friend invite codes and links.
- **Messaging and calls:** encrypted direct and group messaging, in-chat message search, unsend own messages, chat-only voice messages, VoIP voice calls, camera capture and attachment picker in chat, and per-contact private notes.
- **Shared safety plans:** view safety plans that connected Users have **explicitly shared** with them.
- **Documents (shared and personal):** view and download documents explicitly shared by a User, plus upload and manage their own encrypted documents.
- **Notes and incidents (shared and personal):** open full-screen views of notes and incident reports shared by a User, and maintain their own private notes and incident records (with pinning, attachments, trash, and edit history).
- **Location and navigation:** view live locations shared with them on an interactive map, share their own location, and run route navigation to a shared location.
- **Hotlines and activity history (web):** browse the hotline directory and review their own account activity history.
- **Settings and offline sync:** edit profile, email consent, and password; manage Android app permissions; and sync queued offline changes.
- Safe Contacts never receive automatic access to a User's private workspace—every shared plan, document, note, incident, or location requires explicit User selection.

### Staff

Organization support staff using the Olive Tree workspace. Staff accounts are created by an administrator with a temporary password and complete their profile on first sign-in.

- **Consultation availability:** toggle availability on or off (*Available for new requests* / paused) so new safety-plan and support requests are routed only to available staff.
- **Safety inbox (consultations):** view assigned safety-plan consultation tickets (tracked by ticket number), open and review the user's safety plan, collaboratively edit/update plan sections (web consultation workspace), and mark a consultation complete (which closes the consultation ticket).
- **Support conversations:** view and reply to assigned support chats and ticket conversations (with unread/pending action badges and ticket numbers), search messages within a chat, view shared entries attached to the ticket, and close resolved support chats.
- **Escalate to administrator:** flag a support conversation as needing administrator help (`Needs admin`) to hand off complex or urgent cases.
- **Organization live map (Android):** monitor live and last-known user locations on the organization map.
- **Organization settings:** edit profile (full name, date of birth, email, optional phone number), change password, manage Android app permissions, and sign out.

### Administrator

Organization administrators using the Olive Tree workspace. Administrator accounts are provisioned by an existing administrator.

- **Organization overview:** live dashboard counts of accounts per role (**Users**, **Safe Contacts**, **Staff**, **Administrators**) alongside **Open** and **Closed** support ticket totals.
- **Account management:** search accounts by username, name, or role; filter by profile setup status (*All*, *Setup complete*, *Setup required*); create managed accounts for any role (**User**, **Safe Contact**, **Staff**, **Administrator**) with a temporary password; and delete non-protected accounts (protected accounts are badged and cannot be deleted).
- **Staff management (Android):** inspect every staff member's workload card showing total vs. completed safety-plan tasks and total vs. closed support-chat tasks.
- **Safety plans inbox:** view both **Assigned to staff** consultations (with the assigned staff member's name) and the **Waiting for staff** unassigned queue, and open any consultation safety plan.
- **Escalation chats:** review escalated support tickets (`Needs admin`), switch between open and closed tickets, and respond directly in the support conversation.
- **Emergency response center (Android):**
  - Receive immediate in-app pop-up alerts for unacknowledged emergency reports with one-tap **Track** (follows the user on the live map) or **Open response**.
  - Filter emergency alerts by **Active** and **Resolved** tabs with live counts.
  - Open and read the user's encrypted emergency report PDF in the built-in viewer, jump to live map tracking for that user, and mark incidents **Resolved** or **Reopen** them.
- **Organization live map (Android):** view all live and last-known user locations on an interactive map, including targeted user tracking from emergency alerts.
- **Organization settings:** edit profile (full name, date of birth, email, optional phone number), change password, manage Android app permissions, and sign out.

> Staff and administrator access to a User's data is strictly limited to what the user shares with the organization (support tickets, safety-plan consultations, emergency reports, and live-location features) and is enforced by server-side role checks.

## Features available to every account

- Role-appropriate sign-in, first-time onboarding, profile management, and password rotation.
- **Exaggerated invalid-input feedback** on forms (Android and web): any missing or invalid field shakes horizontally and highlights its error state, and shakes again on every repeated failed attempt. Android pairs the shake with a rejection haptic cue. Motion is automatically skipped when system animations are disabled (Android) or when `prefers-reduced-motion` is enabled (web).
- **In-app release update reminder and installer (Android):** checks the signed release manifest, shows an update badge in the workspace header for all four roles, verifies the downloaded APK, and launches the update installer.
- **In-app permissions center (Android):** plain-language explanations, live grant status, and one-tap permission or system-settings actions for Camera, Microphone, Location, Background Location, Notifications, Incoming Calls, Activity Recognition, and Health Connect.
- Neutral, cover-appropriate local notifications that never display message bodies or sender names on the lock screen.
- Optional email notifications (such as friend-invite and live-location alerts) sent strictly to accounts that have granted email communication consent.
- Local persistence with cloud sync when Firebase is configured; local development accounts work offline without Firebase.

## Cover apps

| Cover | Platform | What it includes |
| --- | --- | --- |
| **Daily Digest** | Android | News feed and article reader with category browsing, cover settings, and private-entry trigger controls. Fetching live articles requires connectivity. |
| **Fitness Tracker** | Android | Fitness profile and goal calculator, food/meal logging, calorie and macro totals, workout tracking (including outdoor GPS workouts), vitals, history charts, Health Connect and phone/watch summaries, and an AI fitness coach chat. Sensor data depends on hardware and permissions. |
| **Little Grove (Game)** | Web | Public web cover featuring six playable botanical games (**Nature Pairs**, **Olive Flight**, **Olive Crossing**, **Canopy Breaker**, **Grove 2048**, and **Grove Tetris**) with optional sound effects and a hidden olive gesture in the Account tab for private entry. |

## Web portal

A React + TypeScript app in [`web/`](web/README.md) (**Little Grove** is the public web game cover).

- Public **Little Grove** game cover; private account views; separate staff and administrator workspaces.
- Browser enrollment with a browser PIN, personal private-entry links, Quick Exit, background locking, and an encrypted browser vault.
- Notes and incidents (with attachments and location), contacts, profile, friends and messages, voice messages, safety plans, hotlines, and activity history for Users and Safe Contacts.
- Admin account management and support inbox for administrators; consultation workspace for staff.
- With Firebase configured it uses the same shared services as Android. Without it, registration creates a browser-only account separate from Android local accounts.
- Installable as a PWA. The service worker does not cache private data; there is no fully offline private workspace.
- The web portal is still in progress. See [web/README.md](web/README.md) for setup and platform differences.

## Wear OS companion

- Separate `wear` module with a fitness companion screen and watch tiles.
- Step and heart-rate data from supported sensors, phone/watch sync, and nutrition summaries.
- Requires a compatible paired watch, sensor support, and granted permissions.

## Privacy and security

- The private workspace is gated separately from the visible cover, with failed-entry handling and session locking.
- Encrypted local vault storage using the Android Keystore; client-side encryption for messages, private entries, and uploaded media.
- Server-side ownership and role checks (Firestore/Storage rules and Cloud Functions) for shared services.
- Explicit recipient selection for shared content.
- Screen-capture and recent-app-preview protection exists behind the `screen_capture_protection_enabled` resource toggle, which is currently **false** (disabled).
- Security work is ongoing. These controls are details of an educational prototype, not a claim of audited protection. A cover app does not make Olive Branch impossible to discover.

## Languages and accessibility

- Android language selector: **English, French, Spanish, Arabic, Chinese**, plus system language.
- Screen-reader labels and traversal order, headings, live status announcements, focus handling, and responsive layouts. Color-contrast (WCAG/APCA) checks exist in tests. Dedicated color-blind filters are backlog work.

## Project structure

| Path | Contents |
| --- | --- |
| `app/` | Android app (Kotlin, Jetpack Compose). UI in `ui/users/<role>/<feature>`, covers in `ui/covers`, session logic in `ui/session`, data in `data/`. |
| `wear/` | Wear OS fitness companion. |
| `ppocr-sdk/` | On-device OCR library module. |
| `web/` | React + TypeScript web portal and its Node server. |
| `functions/` | Firebase Cloud Functions (Node 22): accounts, messaging access, safety plans, consultations, support tickets, friend invites, passkey recovery, media tickets, email notifications, fitness AI. |
| `firebase/` | Firestore, Realtime Database, and Storage security rules. |
| `docs/` | [Testing](docs/TESTING.md), [Distribution](docs/DISTRIBUTION.md), and [load testing](docs/LOAD_TESTING.md) guides. |
| `scripts/` | Release packaging, coverage, and test-report scripts. |
| `website/`, `public/` | Public site assets and the published Android release manifest. |

## Getting started

### Install a release (users)

Signed Android releases are downloaded from `https://yourolivebranch.ca/get`; the site QR code uses the same permanent address. Android will ask you to approve installing from outside Google Play. See [Distribution](docs/DISTRIBUTION.md) for how releases are published.

### Build from source (developers)

**Prerequisites:** Android Studio with the Android SDK and JDK 17, Node 22.12+ (for `web/` and `functions/`), and Python 3 (for the full test script).

```bash
git clone https://github.com/macewanCS/F26_D1.git
```

1. Open the project in Android Studio and let Gradle sync.
2. Copy [`local.properties.example`](local.properties.example) entries into the git-ignored `local.properties`: `GEOAPIFY_API_KEY`, `MAPBOX_ACCESS_TOKEN`, `REALTIME_DATABASE_URL`, `VOIP_API_BASE_URL`, `UPDATE_MANIFEST_URL` (these are required for release builds). Never commit real keys.
3. Add your `google-services.json` for Firebase-backed features. Without Firebase configured, the app runs with local-only accounts.
4. Run the `app` configuration on an emulator or device.

Release signing uses `keystore.properties` (see [`keystore.properties.example`](keystore.properties.example) and [Distribution](docs/DISTRIBUTION.md)).

### Firebase App Check debug token (debug builds)

Debug builds need an App Check debug token for protected Firebase features. The token is static on a physical device but changes every time you start a new emulator.

1. Run the app, open Logcat in Android Studio, and filter by `debug token`.
2. Copy the token from the `Firebase App Check debug token: ...` log line.
3. Open the Firebase console link in that log and register the token.

Keep debug tokens out of email, chat, and the repository. Signed release builds use the registered release key instead. For a demo, use the signed release; if a debug build is needed, arrange in-person registration with a project administrator.

### Demo accounts

For the Trusted Person demo, choose the **Safe Contact** account type and sign in as the provisioned username `demosafe`. The User must send a friend request and the contact must accept it before a direct chat opens. The User must explicitly share any document or safety plan.

## Testing

Run the full standard suite (Android unit/build/lint/architecture checks, web tests/build/accessibility checks, Cloud Functions tests and audit, Firebase rules tests):

```bash
./test-all.sh                       # macOS / Linux
./test-all.bat                      # Windows
```

Add isolated Android UI tests on a disposable emulator:

```bash
./test-all.sh --with-device-tests   # macOS / Linux
./test-all.bat -WithDeviceTests     # Windows
```

Device tests erase the emulator's Olive data and use test PIN `246810`; physical phones are rejected. See [Testing](docs/TESTING.md) for options and reports.

Individual suites:

```bash
./gradlew checkArchitecture :app:compileDebugKotlin :app:testDebugUnitTest   # Android
npm --prefix web test && npm --prefix web run build                          # Web
npm --prefix functions test                                                  # Cloud Functions
```

CI (`.github/workflows/ci.yml`) runs Android unit tests with a coverage threshold on pull requests.

## Backend and deployment

- Firebase project config lives in `firebase.json`; rules are in `firebase/` and functions in `functions/`.
- **Deploy functions individually** (`firebase deploy --only functions:<name>`). Do not run a blanket `--only functions`: it can overwrite functions a teammate redeployed from another branch.
- Email notifications need the `SMTP_PASS` secret.
- Web hosting notes are in [web/HOSTINGER_DEPLOYMENT.md](web/HOSTINGER_DEPLOYMENT.md). Android release publishing is documented in [Distribution](docs/DISTRIBUTION.md).

## Known limitations

- Offline support varies by feature. Do not assume uncached documents, message delivery, live locations, routes, news, or cloud account operations work offline; safety-plan edits queue until you reconnect.
- Firebase-backed messaging, sharing, organization actions, and sync need the corresponding backend configuration.
- Address suggestions and navigation need network access plus Geoapify and Mapbox keys; camera, location, and notifications need device permissions.
- Media/document sharing and some upload-recovery paths still need further verification.