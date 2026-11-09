# Hostinger deployment

Olive Branch needs two Hostinger Node.js apps: one website and one VoIP API.

| Address                   | Root directory         | Build command   | Entry file                    |
| ------------------------- | ---------------------- | --------------- | ----------------------------- |
| `yourolivebranch.ca`      | `web`                  | `npm run build` | `server/productionServer.mjs` |
| `voip.yourolivebranch.ca` | `website/voip-library` | None            | `src/start.js`                |

The main website serves all browser pages from one build:

- `https://yourolivebranch.ca/` — cover apps and private user entry
- `https://yourolivebranch.ca/get` — downloads and installation help
- `https://yourolivebranch.ca/admin` — staff and administrator workspace

Delete the old `get.` and `admin.` Hostinger apps after the main deployment is
working. Do not set `VITE_SITE_MODE`; the URL path now chooses the page.

Use Node.js 22 or 24. Hostinger supplies `PORT`, and both servers listen on the
provided port and all container interfaces.

## Main website environment

Add these public browser settings to the `yourolivebranch.ca` app:

- `VITE_FIREBASE_API_KEY`
- `VITE_FIREBASE_AUTH_DOMAIN`
- `VITE_FIREBASE_PROJECT_ID`
- `VITE_FIREBASE_STORAGE_BUCKET`
- `VITE_FIREBASE_APP_ID`
- `VITE_FIREBASE_APPCHECK_SITE_KEY`
- `VITE_USE_FIREBASE_EMULATORS=false`

The download page and its permanent QR use `/download/android`. The server reads the validated
`public/android/latest.json` release manifest and redirects to that signed, immutable APK. The app
uses `/api/android/releases/latest` to check the same manifest for updates.

Every `VITE_` variable is readable by visitors. Never put a Firebase Admin
service account, private key, signing keystore, password, FatSecret secret, or
LiveKit secret in a `VITE_` variable or under `web/src`.

## VoIP environment

Add the following only to the `voip.yourolivebranch.ca` app:

- `FIREBASE_DATABASE_URL`
- `FIREBASE_SERVICE_ACCOUNT_JSON`
- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`

Store the full service-account JSON in Hostinger's secret environment settings;
never commit it or place it under the public web root. See
`website/voip-library/readme.md` for the API and permission details.

## Firebase and storage setup

1. Add `yourolivebranch.ca` to Firebase Authentication authorized domains.
2. Register it as the Firebase App Check web domain.
3. Restrict the public Firebase API key to the Firebase APIs the browser uses.
4. Deploy the reviewed Firestore, Realtime Database, and Storage rules.
5. Allow `https://yourolivebranch.ca` in Cloudflare R2 CORS. Permit `PUT`,
   `GET`, and `HEAD`, required signed headers, and expose `ETag`.
6. Confirm `https://yourolivebranch.ca/api/health` and
   `https://voip.yourolivebranch.ca/health` both report healthy responses.
7. Test `/get`, user sign-in, private entry, Quick Exit, `/admin`, document
   upload, support chat, and a two-device VoIP call.

Opening `/admin` selects the organization portal but never grants staff or
administrator access. Firebase custom claims remain the authority. Android is
still required for background location sharing, OCR capture, and continuous
watch sensor data.

`app/google-services.json` is Android client configuration, not a Firebase
Admin credential. It may remain in the repository when its API key is properly
restricted. Never commit a downloaded Admin SDK service-account JSON file.
