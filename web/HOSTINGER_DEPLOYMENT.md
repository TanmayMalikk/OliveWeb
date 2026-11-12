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

The permanent QR directly triggers `/download/android`, where the server reads
the validated `public/android/latest.json` release manifest and redirects to that signed, immutable
APK. The app uses `/api/android/releases/latest` to check the same manifest for updates.

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
5. In Cloudflare R2 → media bucket → Settings → CORS Policy, allow
   `https://yourolivebranch.ca` as an origin, `PUT`, `GET`, and `HEAD` as
   methods, and `Content-Type` as an allowed header. Expose `ETag` for
   multipart uploads. Include any other hostname only if it serves the app.
   The bucket can stay private because Firebase Functions issue signed URLs.
6. Confirm `https://yourolivebranch.ca/api/health` and
   `https://voip.yourolivebranch.ca/health` both report healthy responses.
7. Test `/get`, user sign-in, private entry, Quick Exit, `/admin`, document
   upload, support chat, and a two-device VoIP call.

## Document recovery

Set the Firebase Functions secret `MEDIA_RECOVERY_MASTER_KEY` to a securely
generated Base64-encoded 32-byte key before deploying
`mediaCreateUpload`, `mediaUploadPartTicket`, `mediaCompleteUpload`,
`mediaAccountAccessKey`, and `mediaStoreAccountAccessKey`. Keep this key stable.
Existing encrypted recovery records depend on it.

Deploy the Functions before releasing the website and Android clients.
Documents stay encrypted in R2. Firebase can recover a document key for
an authenticated account with current document access. Existing R2 files
upgrade when the owner unlocks the updated Android app or opens the web
Documents page on a device that holds the original key. Files in the
legacy Android storage format need a new upload.

Upload a PDF on Android with the website closed, then sign in on the web and
open it with Android closed. Repeat in reverse. With both clients open and
online, check that a completed upload and a revocation appear in the other
client without a refresh. A web upload larger than 16 MiB exercises multipart
storage.

Opening `/admin` selects the organization portal but never grants staff or
administrator access. Firebase custom claims remain the authority. Android is
still required for background location sharing, OCR capture, and continuous
watch sensor data.

`app/google-services.json` is Android client configuration, not a Firebase
Admin credential. It may remain in the repository when its API key is properly
restricted. Never commit a downloaded Admin SDK service-account JSON file.
