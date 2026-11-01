# Olive Branch web portal

React + TypeScript alongside the Android project. Android publishes an owner-only
14-day fitness summary so the signed-in web cover can display real activity.

## Run locally

Requires Node 22.12+ (or another version supported by the installed Vite version).

```powershell
cd web
npm ci
npm run dev
```

Open **http://127.0.0.1:5173**. With no Firebase configuration, registration creates
an encrypted browser-only account. There are no seeded accounts or passwords.
Accounts stored in the Android app's local vault cannot be read by a browser.
Use fictional information when trying local mode.

```powershell
npm test             # Policy, encryption, and news network-boundary tests
npm run test:e2e     # Chrome: account flows, private entry, covers, responsive layouts, accessibility
npm run build       # Type checking + production bundle
npm start           # Production preview at http://127.0.0.1:4173
```

The browser tests use an installed Chrome and isolated, fictional browser stores.
Firebase rule checks remain in `../firebase` (`npm test` there starts demo emulators).

## Production structure

| Address                    | Build command            | Behavior                                                                                                                                                                                                           |
| -------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `yourolivebranch.ca`       | `npm run build:user`     | A pathless user SPA. `/` shows the cover saved for this browser: Game, News, Food, or Fitness. Game is the default. Login, onboarding, PIN entry, and Safe App are in-memory screens and the address stays `/`. |
| `get.yourolivebranch.ca`   | `npm run build:download` | Download and installation instructions at `/`.                                                                                                                                                                     |
| `admin.yourolivebranch.ca` | `npm run build:admin`    | Staff and administrator sign-in and workspace at `/`. Personal and safe-contact roles are rejected here.                                                                                                           |

The production setup uses three Hostinger Node.js Web Apps from this repository,
one build command and domain per row. Hostinger supports this monorepo layout: set
the **Root directory** to `web`, **Output directory** to `dist`, **Entry file** to
`server/productionServer.mjs`, and Node.js to version 22 or 24. Use the matching
build command from the table. The Express entry file supplies SPA fallback,
security headers, dynamic manifests, and the same-origin news API.

All three deployments use the same Firebase project and public web configuration.
Add the values from `.env.example` in each Hostinger Web App's **Environment
variables** screen. Variables beginning with `VITE_` are intentionally compiled
into browser JavaScript and must never contain service-account JSON, private keys,
FatSecret credentials, signing keys, passwords, or other server authorization.
FatSecret remains in Firebase Functions secrets. `npm run security:check` runs
before every production build and rejects common private credential formats from
the public web source.

Hostinger Business/Unlimited or Cloud hosting is required for this Node.js setup.
A static-only plan can display the built React pages, but the News cover API and
dynamic manifests will not work. Add `yourolivebranch.ca`,
`get.yourolivebranch.ca`, and `admin.yourolivebranch.ca` as separate Web Apps;
each counts as one Hostinger Node.js website. Enable automatic GitHub deployment
only after the production branch and environment variables are confirmed.

The four user covers are states of the first SPA, rather than pages or subdomains.
The chosen cover is saved in the browser enrollment/profile. Two people can open
the same `https://yourolivebranch.ca/` address and see different covers. The Game
cover exposes no login or download links. Five quick taps on its Little Grove
brand starts private entry. Food, News, and Fitness retain their configured
trigger input. Quick Exit returns to the saved cover at `/`.

Local routes such as `/olive`, `/daily-digest`, `/fitness`, `/game`, and
`/account/sign-in` are accepted as migration/development inputs, then immediately
removed from the address bar. Personal entry links arrive as
`https://yourolivebranch.ca/entry#<random-token>`; both the path and fragment are
replaced with `/` as soon as the app reads them.

### Shared code, multiple website addresses

`site.config.json` is the address map shared by the three builds, Quick Exit,
personal links, and PWA manifests. `VITE_SITE_MODE` is committed through
`.env.user`, `.env.download`, and `.env.admin`; Hostinger may also set it explicitly.
Configure all three domains as Firebase authorized domains and configure Storage
CORS before enabling them. Browser storage stays origin-specific, so account
enrollment lives on the main domain while the public download and admin sites keep
their own sessions/storage.

The Android UI remains Kotlin in `app`; shared web UI is React in `web/src`.
There are no duplicated React applications or separate cover repositories.

## Shared Firebase accounts

The `Olive Branch Web` Firebase app is registered in the same project as Android.
Copy
`.env.example` to `.env.local` and supply that web app's public Firebase config.
Do not copy service-account JSON, Android signing credentials, or server secrets.
Partial Firebase configuration must be completed before enabling shared mode.

The web adapter preserves mobile contracts:

- Role aliases: `username@users.olive.invalid`, `@safe.olive.invalid`,
  `@staff.olive.invalid`, and `@admin.olive.invalid`.
- Profiles: Storage `accounts/{uid}/profile.json`, with the same profile fields.
- History: Storage `accounts/{uid}/history/{settings,triggers,checkouts,private_entries}/{id}.json`.
- Messages/friends: existing Firestore directory, request, friendship, friend-list,
  conversation, and message collections. Firestore disk persistence is disabled.
  The web client never replaces a different device's public encryption key; until
  multi-device key linking is complete, an account already linked to Android opens
  secure messages on Android instead of weakening or silently rotating its keys.
- Safety plans: the user, safe-contact, and staff web views call the same
  `safetyPlan*` callable Functions as Android, including field revisions,
  consultation assignment, shared read access, and staff availability.
- Organization actions: existing callable Functions in `northamerica-northeast1`;
  admin authorization stays on the server.
- Web-only entry registry: Firestore `webEntryLinks/{uid}` stores a SHA-256 token
  hash and server timestamp. The owner can get/rotate/delete only their own record;
  anonymous reads and collection listing are denied.

The owner-only fitness Storage rule was tested and deployed to `olive-21239`.
Review and deploy later Firestore or Storage changes separately. Authorize the web
origins in Firebase Authentication and configure Storage CORS for authenticated
browser downloads. `storage.cors.example.json` is a local-origin example, not an
access-control policy or a production deployment command.

Set `VITE_USE_FIREBASE_EMULATORS=true` only with a `demo-` Firebase project ID.
The adapter uses Auth 9099, Firestore 8080, Storage 9199, and Functions 5001.
The existing rule-test emulator uses Firestore 8180 separately.

## Android testing

The website does not publish an Android APK. Connect a phone to Android Studio,
select the device, and use Run to build and install the app for testing. The
download page keeps iPhone and iPad web app installation instructions.

## PWA and responsive UI

The website responds to viewport space, container size, orientation, keyboard, and
touch interaction; it does not guess device models from the user agent. It uses
fluid type, responsive grids, touch-sized targets, and bottom navigation on narrow
private-workspace layouts. Reduced-motion settings are respected.

Each cover has a stable manifest ID/start URL, name, and icon. iPhone/iPad users
open their chosen cover in Safari, use Share → Add to Home Screen, and enable
Open as Web App if offered. Keep the original personal link separately; the
installed start page remains a cover. Icons can be regenerated with
`node scripts/icons.mjs`.

The production-only service worker does **not** cache private pages, account data,
API responses, or articles. Offline navigations show a neutral Little Grove pause
page. This intentionally does not provide a fully offline application shell.

## Privacy boundaries and platform differences

- Personal links contain 256-bit random tokens in the URL fragment. Fragments are
  not sent in HTTP requests; the app removes the fragment from the current address
  after reading it. This reduces accidental logging, but is not a promise that a
  browser, synced history, extension, or bookmark will never retain a link.
- A link is an additional entry gate, **not authentication**. Account ownership
  and the enrolled PIN/password are still checked. Wrong-account and replaced
  links cannot open the private workspace. Cloud revocation is checked online.
- Local account data uses random AES-256-GCM vault keys, PBKDF2-SHA256 (600,000
  rounds), separate salts, and per-write nonces. Passwords/PINs are not stored in
  plaintext. The browser vault is separate from Android Keystore and does not
  provide hardware-backed isolation or protection from malicious same-origin JS.
- Quick Exit/Escape remove private React content and clear active in-memory vault
  keys. Backgrounding locks the workspace. Browser Back never restores an unlocked
  workspace. Private view state and drafts are not stored in URL parameters.
- Idle locking defaults to 60 seconds plus a 15-second warning. Browser screen
  readers do not expose Android's accessibility-timeout API; manually check reading
  comfort with VoiceOver/TalkBack and keyboard navigation before real use.
- Websites cannot implement Android's screenshot blocking, reliable screenshot/
  recording detection, recent-app preview protection, or a system-wide silent
  exit. Quick Exit cannot erase browser history or prevent screenshots.
- Notes, incident records, edit history, pinning, trusted contacts, profile updates,
  password/PIN changes, and settings/trigger/checkout history have web workflows.
  Trash currently expires after **10 seconds**, matching the mobile test setting.
- Messaging requires Firebase; local accounts cannot communicate with other users.
  Speech/video calling is not implemented in either version by this work.
- The profile language preference syncs; this web UI currently uses English.
  Native translation coverage has not been ported one-for-one.
- Publisher restrictions remain in place. The web reader sanitizes available
  article HTML and links to the original when Google News resolution, paywalls,
  or publisher restrictions prevent reading. It does not promise that every
  article will open in-page or bypass a subscription.
- Food checkout is a demonstration, matching the mobile cover: no order is placed
  and no payment is charged. Card fields are transient and never stored or sent.
- The Fitness cover logs calories/macros in a browser and displays the Android app's
  owner-only 14-day steps, distance, calories, and Wear OS heart-rate summary. A
  browser does not claim to count background steps.
- Browser camera preview and current-location lookup are available while their page
  is open. Android remains the source for OCR, background location sharing,
  navigation, and encrypted media upload/viewing because their keys and background
  services are device-held.

Do not describe this as a deployed production site or complete native parity until
the remaining platform/translation differences and real Firebase integration have
been evaluated with the project team.

Staff and administrator authority is accepted only from server-managed Firebase
Auth custom claims. A role written in a profile or inferred from an organization
email alias cannot grant privileged backend access.
