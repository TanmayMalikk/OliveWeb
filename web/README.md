# Olive Branch web portal

React + TypeScript, alongside the existing Android project. No Android source or
build configuration is changed. All domains below are planned; this implementation
runs locally and does not register or deploy a domain.

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

## Routes

| Planned address | Local route | Behavior |
| --- | --- | --- |
| `yourolivebranch.ca` | `/` | Little Grove, a playable nature-pairs memory game. Never restores the private workspace. |
| `yourolivebranch.ca/download` | `/download` | Android download/QR when configured; iPhone/iPad Safari installation walkthrough; onboarding link. |
| `yourolivebranch.ca/account` | `/account` | Account introduction, registration, sign-in, and installation links. |
| Account onboarding | `/account/create` | Credentials → cover selection → profile → browser PIN → personal-link handoff. |
| Returning account | `/account/sign-in` | Reads the saved profile and routes to its linked cover. No private workspace is opened. |
| Replace a lost link | `/account/link` | Password-authenticated link replacement. The previous link stops working. |
| Personal entry | `/entry#<random-token>` | Valid owner link + enrolled-browser PIN; on another device, account sign-in and browser enrollment first. |
| Olive | `/olive` | Menu, search/filter, ingredient choices, favourites, cart, demo checkout. |
| Daily Digest | `/daily-digest` | Live Google News feed, search, location/world scope, bookmarks, reader where available. |
| Better Calculator | `/calculator` | Arithmetic/scientific expressions, graph, zoom/pan, table, derivative, quadratic roots, 30 mobile math tools, statistical plots. |
| Game | `/game` | Little Grove memory game and Olive Flight. |

Cover domains are intentionally not hardcoded. Local paths use one origin so local
accounts and enrollment remain available across covers. Independent production
domains have separate browser stores and Firebase sessions; this implementation
uses fresh sign-in/enrollment rather than an automatic authentication handoff. Never pass
passwords, ID tokens, or refresh tokens in cover redirect URLs.

### Shared code, multiple website addresses

`site.config.json` is the address map shared by the React router, account cover
redirects, Quick Exit, and the server's PWA manifests. All `origin` fields are
blank for local development. Old `/cover/<name>` URLs continue to work; navigation
uses the short canonical URLs above. `/` stays the game disguise and `/account`
stays the onboarding site. No account or download links appear on the game.

When production domains are chosen, set `publicOrigin` to the main HTTPS origin
and each cover's `origin` to its HTTPS origin, for example
`https://news.example.com` (no path). Deploy this same code/build behind all those
domains with SPA fallback and `/api`, manifest, and download server routes. A
configured cover domain's `/` displays that cover. Rebuild and restart after
changing the configuration. Multiple Vercel domains may target the same project;
the current Express server endpoints must also be deployed using a compatible
server/service setup rather than publishing only `dist` as static files.

Public account/download links and newly generated personal links target
`publicOrigin`; cover navigation targets the selected cover's origin. Navigation
across origins clears active browser vault keys. Cloud account profiles remain in
the same Firebase project. **There is no automatic cross-domain sign-in handoff**:
sign in/enroll again where required. Configure Firebase authorized domains, Storage
CORS, and hosting before enabling those domains. Local browser-only accounts and
saved games stay with the origin that created them. Existing personal bookmarks
must be replaced or supported by the old domain when domains change.

The Android UI remains Kotlin in `app`; shared web UI is React in `web/src`.
There are no duplicated React applications or separate cover repositories.

## Shared Firebase accounts

Register a **Web app in the same Firebase project** as Android, then copy
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
- Organization actions: existing callable Functions in `northamerica-northeast1`;
  admin authorization stays on the server.
- Web-only entry registry: Firestore `webEntryLinks/{uid}` stores a SHA-256 token
  hash and server timestamp. The owner can get/rotate/delete only their own record;
  anonymous reads and collection listing are denied.

The additional entry-registry rules in `../firebase/firestore.rules` must be
reviewed and deployed to the intended project before cloud link creation works.
No Firebase configuration or rules were deployed by this work. Authorize the web
origins in Firebase Authentication and configure Storage CORS for authenticated
browser downloads. `storage.cors.example.json` is a local-origin example, not an
access-control policy or a production deployment command.

Set `VITE_USE_FIREBASE_EMULATORS=true` only with a `demo-` Firebase project ID.
The adapter uses Auth 9099, Firestore 8080, Storage 9199, and Functions 5001.
The existing rule-test emulator uses Firestore 8180 separately.

## Android APK / QR

A QR is shown only when a real APK exists **and** a phone-reachable origin is set.
An unavailable download is displayed honestly; no fake QR or placeholder APK is served.

```powershell
$env:ANDROID_APK_PATH = 'C:\absolute\path\to\signed-release.apk'
$env:PUBLIC_ORIGIN = 'http://YOUR-COMPUTER-LAN-IP:5173'
$env:HOST = '0.0.0.0'
npm run dev
```

Only the operator-configured file is downloadable at `/download/android`; request
parameters cannot select local files. This checkout's ignored `.env.local` uses
the existing `app/build/outputs/apk/debug/app-debug.apk`, explicitly approved for
local university testing, and labels it as a development build. Both server entry
points read `.env.local`; existing process environment variables take precedence.
Use a properly signed release APK before public distribution. There is no release
keystore configured in this checkout.
Phone downloads cannot use the computer's localhost address. LAN HTTP can deliver
an APK, but the web account/PWA needs trusted HTTPS on a phone for Web Crypto.
The current QR points to `http://10.60.110.161:5173/download/android`; both devices must
be on the same reachable network. Update `PUBLIC_ORIGIN` if the computer's address
changes. Windows firewall settings have not been changed.

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
  Native translation coverage has not been ported one-for-one. The 30 advanced
  calculator tools reuse the mobile tool names/examples, and plotting includes
  histogram, box, dot, scatter, and XY line views with accessible data tables.
- Publisher restrictions remain in place. The web reader sanitizes available
  article HTML and links to the original when Google News resolution, paywalls,
  or publisher restrictions prevent reading. It does not promise that every
  article will open in-page or bypass a subscription.
- Food checkout is a demonstration, matching the mobile cover: no order is placed
  and no payment is charged. Card fields are transient and never stored or sent.

Do not describe this as a deployed production site or complete native parity until
the remaining platform/translation differences and real Firebase integration have
been evaluated with the project team.

Before enabling organization accounts in production, review the existing
`functions/index.js` authorization: `roleFromUser` falls back to the role inside
an owner-writable profile when custom claims are absent. Administrative authority
must come from trusted server-managed claims. This pre-existing backend behavior
was left unchanged to avoid silently breaking legacy mobile organization accounts;
the web client requires matching custom claims for staff/admin sign-in, which is
not a substitute for fixing server authorization.
