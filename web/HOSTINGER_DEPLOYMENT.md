# Hostinger deployment

Create three **Node.js Web Apps** from the same GitHub repository. These settings
assume Hostinger Business/Unlimited or Cloud hosting.

| Domain                     | Root directory | Build command            | Output directory | Entry file                    |
| -------------------------- | -------------- | ------------------------ | ---------------- | ----------------------------- |
| `yourolivebranch.ca`       | `web`          | `npm run build:user`     | `dist`           | `server/productionServer.mjs` |
| `get.yourolivebranch.ca`   | `web`          | `npm run build:download` | `dist`           | `server/productionServer.mjs` |
| `admin.yourolivebranch.ca` | `web`          | `npm run build:admin`    | `dist`           | `server/productionServer.mjs` |

Hostinger may offer only `npm run build` in its build-command menu. That is
usable: add `VITE_SITE_MODE` as an environment variable on each separate app
(`user`, `download`, or `admin`) before deploying. Vite reads this value during
the build, so each app gets the correct site even though all three use the same
repository and build script. Redeploy after changing a `VITE_` variable.

Use Node.js 22 or 24. Hostinger supplies `PORT`; the server listens on that port
and on all container interfaces. Each deployment performs type checking and a
public-source credential scan before Vite creates the production bundle.

## Public build variables

Add these values to each Web App in Hostinger's Environment variables screen:

- `VITE_FIREBASE_API_KEY`
- `VITE_FIREBASE_AUTH_DOMAIN`
- `VITE_FIREBASE_PROJECT_ID`
- `VITE_FIREBASE_STORAGE_BUCKET`
- `VITE_FIREBASE_APP_ID`
- `VITE_FIREBASE_APPCHECK_SITE_KEY`
- `VITE_USE_FIREBASE_EMULATORS=false`
- `VITE_SITE_MODE` (`user` on the main site, `download` on `get`, and `admin` on `admin`)

On `get.yourolivebranch.ca`, the download button uses `/download/android`,
which redirects to the public Firebase Storage object
`public/android/olive-branch.apk`. Upload the debug APK to this path when you
want to publish a new download. `ANDROID_APK_URL` may override the Firebase
URL, but it must still point to this exact object.

These are browser configuration, not private server credentials. Every variable
whose name begins with `VITE_` is readable by site visitors after the build.

Keep FatSecret credentials in Firebase Functions secrets. Never place a Firebase
service-account JSON file, private key, signing keystore, password, or OAuth client
secret in Hostinger build variables prefixed with `VITE_` or anywhere under
`web/src`.

## Firebase console setup

Before opening the sites to users:

1. Add all three domains to Firebase Authentication's authorized domains.
2. Register a Firebase Web App and use its public browser configuration above.
3. Register all three domains for Firebase App Check with reCAPTCHA Enterprise,
   then use the public site key as `VITE_FIREBASE_APPCHECK_SITE_KEY`.
4. Restrict the Firebase API key in Google Cloud to the Firebase APIs the app uses.
5. Deploy the reviewed Firestore and Firebase Storage rules. Configure Cloudflare R2
   bucket CORS for all three HTTPS origins (`yourolivebranch.ca`, `get.yourolivebranch.ca`,
   and `admin.yourolivebranch.ca`): allow `PUT` and `GET`, allow the signed upload headers,
   and expose the `ETag` response header for multipart uploads. The web document uploader
   sends ciphertext directly to R2 using short-lived tickets from Firebase Functions.
6. Deploy the Firebase Functions update that requires trusted custom claims for
   staff and administrator authority.
7. Confirm `https://yourolivebranch.ca/api/health` returns `{ "ok": true }`, then
   test registration, private entry, Quick Exit, News, staff sign-in, and admin
   sign-in on the live domains.

Staff and administrator pages require Firebase Auth accounts with trusted `STAFF` or
`ADMIN` custom claims; setting `VITE_SITE_MODE=admin` only selects the portal and never
grants a role. Encrypted support messages still require the browser's chat key to be
approved through the existing device flow. Camera capture, browser geolocation, and
foreground web routing depend on the browser's permission prompts. Android remains
needed for background location sharing, OCR capture, and continuous watch sensor data.

`app/google-services.json` is Android client configuration. It is safe to keep in
the repository when its API key is properly restricted; it is not a Firebase Admin
service-account key. Never commit a downloaded Admin SDK/service-account JSON file.
