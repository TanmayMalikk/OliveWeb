# Hostinger deployment

Create three **Node.js Web Apps** from the same GitHub repository. These settings
assume Hostinger Business/Unlimited or Cloud hosting.

| Domain | Root directory | Build command | Output directory | Entry file |
| --- | --- | --- | --- | --- |
| `yourolivebranch.ca` | `web` | `npm run build:user` | `dist` | `server/productionServer.mjs` |
| `get.yourolivebranch.ca` | `web` | `npm run build:download` | `dist` | `server/productionServer.mjs` |
| `admin.yourolivebranch.ca` | `web` | `npm run build:admin` | `dist` | `server/productionServer.mjs` |

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
5. Deploy the reviewed Firestore and Storage rules and configure Storage CORS for
   these HTTPS origins.
6. Deploy the Firebase Functions update that requires trusted custom claims for
   staff and administrator authority.
7. Confirm `https://yourolivebranch.ca/api/health` returns `{ "ok": true }`, then
   test registration, private entry, Quick Exit, News, staff sign-in, and admin
   sign-in on the live domains.

`app/google-services.json` is Android client configuration. It is safe to keep in
the repository when its API key is properly restricted; it is not a Firebase Admin
service-account key. Never commit a downloaded Admin SDK/service-account JSON file.
