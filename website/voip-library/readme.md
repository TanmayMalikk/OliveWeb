# VoIP library

This package provides a Firebase-authenticated HTTP API for LiveKit calls.
Firebase verifies user identity and stores call state in Realtime Database;
LiveKit access tokens are generated on the server and must never be generated
or signed by the Android client.

Run package commands from this directory.

## Setup

Install dependencies and configure Firebase Application Default Credentials
(for example, with `GOOGLE_APPLICATION_CREDENTIALS` pointing to a service
account file outside the repository). The service account needs Firebase
Authentication token-verification access, Realtime Database access, and
Firebase Cloud Messaging access.

### LiveKit Cloud

1. Sign in to the [LiveKit Cloud dashboard](https://cloud.livekit.io/) and
   create a project. Choose a region near the Android users.
2. Open the project's credentials/settings and create an API key. Copy the
   project's WebSocket URL (`wss://...`), API key, and API secret. Do not put
   the API secret in the Android app, source control, or a public environment
   file.
3. In Hostinger's Node.js application environment settings, configure
   `LIVEKIT_URL`, `LIVEKIT_API_KEY`, and `LIVEKIT_API_SECRET`. Keep them in
   hosting-provider environment/secret configuration.
4. Configure Firebase Admin credentials and `FIREBASE_DATABASE_URL` in the
   same Hostinger application, install dependencies, and start this package
   with `npm start`. It must be reachable over HTTPS through your domain or
   reverse proxy.
5. In Android `local.properties`, set `VOIP_API_BASE_URL` to the deployed
   HTTPS API origin. This is the API URL, not the LiveKit URL.
6. First test `GET /health`. Then create a call and accept it with two
   authenticated Firebase users. The accepted participant requests
   `POST /calls/token`; the response's `serverUrl` and `token` are used by the
   LiveKit Android SDK. Clients receive only a room-scoped token, never the
   LiveKit API secret.

The API currently issues one-hour LiveKit tokens. LiveKit tokens are JWTs
signed with the API secret and carry participant identity and room grants.
See LiveKit's [access-token documentation](https://docs.livekit.io/frontends/reference/tokens-grants/).

Set these environment variables:

| Variable                | Required | Description                                                        |
|-------------------------|----------|--------------------------------------------------------------------|
| `FIREBASE_DATABASE_URL` | Yes      | Realtime Database URL for the Firebase project                     |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Host recommended | Full service-account JSON stored as a secret environment value |
| `LIVEKIT_URL`           | Yes      | LiveKit Cloud websocket URL, such as `wss://example.livekit.cloud` |
| `LIVEKIT_API_KEY`       | Yes      | LiveKit API key                                                    |
| `LIVEKIT_API_SECRET`    | Yes      | LiveKit API secret; keep it on the server                          |
| `PORT`                  | Host only| Hostinger assigns this automatically; do not set it manually       |
| `VOIP_API_PORT`         | No       | Local override used when `PORT` is absent; defaults to `3000`      |
| `VOIP_API_HOST`         | No       | HTTP listener host; defaults to `0.0.0.0`                          |
| `FIREBASE_CALLS_PATH`   | No       | Realtime Database root for calls; defaults to `calls`              |
| `FIREBASE_USERS_PATH`   | No       | Realtime Database root for user device tokens; defaults to `users` |

### Hostinger environment

In Hostinger’s Node.js app environment, set:

```text
LIVEKIT_URL
LIVEKIT_API_KEY
LIVEKIT_API_SECRET
FIREBASE_DATABASE_URL
FIREBASE_SERVICE_ACCOUNT_JSON
```

`LIVEKIT_URL` is the project’s `wss://...` URL. Store the complete Firebase
service-account JSON in `FIREBASE_SERVICE_ACCOUNT_JSON` as a secret environment
value. This avoids relying on a filesystem path that may not be mounted inside
Hostinger's isolated Node.js runtime. For local development,
`GOOGLE_APPLICATION_CREDENTIALS` remains supported when the inline variable is
not set.

Hostinger provides `PORT` when it starts the app. Do not add `PORT` or
`VOIP_API_PORT` to the Hostinger environment; the server reads Hostinger's
assigned value automatically. Use `VOIP_API_PORT` only for a local run.

Run the unit tests with:

```bash
npm install
npm test
```

Start the API with:

```bash
npm start
```

Deploy behind HTTPS. The service accepts Firebase ID tokens in
`Authorization: Bearer <Firebase ID token>`; it does not use a shared API token.

## Realtime Database records

The API stores calls at `calls/{callId}` with this shape:

```json
{
  "callerId": "firebase-user-uid",
  "name": "Support",
  "status": "active",
  "participants": {
    "another-user-uid": { "status": "pending" }
  }
}
```

The caller is treated as accepted. Other users can obtain a LiveKit token only
when their participant status is `accepted`. Only the caller can end a call or
send invitations, and notifications can be sent only to pending participants.
Ending a call changes its `status` to `ended` and records `endedAt`.

User device registration tokens are stored under
`users/{userId}/fcmTokens/{deviceId}`. Clients cannot read or write call or
push-token records directly; the Admin SDK accesses them through this API.
Call creation sends a data-only FCM invitation to each participant. Its
response includes `notificationFailures` when an invitation could not be
delivered to all of a participant's devices; the call itself remains created.

The Android client can obtain an FCM token before the user signs in and stores
it locally. Registering it on the server requires a Firebase ID token, so call
`VoipPushTokenRegistration.registerCurrentToken(context)` after sign-in.
Refreshes are registered automatically when an account is signed in; if the
token refreshes while signed out, call `registerCurrentToken` after the next
sign-in. Call `unregisterCurrentDevice(context)` before signing out to detach
this device from that user's invitation list.

The LiveKit room name is derived as `call-{callId}`. The API creates this record through `POST /api/calls`. Firebase Realtime
Database rules prevent clients from reading or writing calls and push tokens.
The API still checks ownership and participant status before issuing
credentials.

## HTTP API

All routes except `GET /health` require a valid Firebase ID token.

- `POST /auth/verify` returns the verified user's UID and, when available,
  email. Other routes verify the token in the same way, so this route is
  optional for clients.
- `POST /calls/token` accepts `{"callId":"..."}` and returns `callId`,
  `serverUrl`, `roomName`, and a LiveKit room token scoped to the verified
  Firebase UID. Only active-call members with accepted status may connect.
- `POST /api/calls` accepts `{"name":"...","callerId":"...","participantIds":[...]}`
  and creates the call record. `callerId` must match the verified Firebase UID;
  every invitee must be an existing Firebase Auth user, and invitees start with
  `pending` status. The API sends invitations after creating the call.
- `GET /api/calls` lists active calls for the authenticated caller or accepted
  participant.
- `GET /api/calls/invitations?userId=...` lists pending invitations for the
  authenticated UID. The supplied `userId` must match the Firebase identity.
- `POST /api/calls/{callId}/accept` and `/decline` accept `{"userId":"..."}`.
  The UID must match the authenticated identity, and only pending invitees may
  respond.
- `POST /calls/end` accepts `{"callId":"..."}`. Only the call owner can end it;
  the call status is then updated in Realtime Database.
- `DELETE /api/calls/{callId}` remains available as a compatibility alias for
  ending a call.
- `POST /notifications/send` accepts `{"callId":"...","recipientId":"..."}`.
  Only the owner can re-send an invitation to a pending participant. The
  invitation is sent via Firebase Cloud Messaging using registered device
  tokens.
- `POST /notifications/register` accepts `{"deviceId":"...","token":"..."}`.
  The token is stored under the authenticated Firebase UID, not an arbitrary
  user ID supplied by the app.
- `DELETE /notifications/register` accepts `{"deviceId":"..."}` and removes
  this device's registration for the authenticated Firebase UID.
- `GET /health` is public and reports HTTP process health only.

Errors use JSON in the form `{"error":{"code":"...","message":"..."}}`.

## Local integration test

The route tests use Firebase and LiveKit fakes. To exercise the API against a
local Realtime Database and Auth emulator:

1. Start the emulators from the repository root:

   ```bash
   firebase --config firebase/emulator.json emulators:start --only auth,database
   ```

2. In another terminal, configure local Admin credentials (a test-project
   service account stored outside the repository), and start the API with:

   ```bash
   export GCLOUD_PROJECT=demo-olive-rules
   export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
   export FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9100
   export FIREBASE_DATABASE_URL=https://demo-olive-rules-default-rtdb.firebaseio.com
   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/test-service-account.json
   export LIVEKIT_URL=wss://your-test-project.livekit.cloud
   export LIVEKIT_API_KEY=your-test-key
   export LIVEKIT_API_SECRET=your-test-secret
   npm start
   ```

3. Create two Auth-emulator users with the Firebase Auth emulator REST API,
   using different email/password pairs. Sign each in through
   `accounts:signInWithPassword` and retain the returned `idToken` values.
   Never use production users or credentials for this test.
4. Create a call using caller A's ID token and caller A's UID:

   ```bash
   curl -X POST http://127.0.0.1:3000/api/calls \
     -H "Authorization: Bearer $CALLER_ID_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"name":"Local test","callerId":"CALLER_UID","participantIds":["RECIPIENT_UID"]}'
   ```

5. Accept the returned call as caller B by posting to
   `/api/calls/CALL_ID/accept` with caller B's token and
   `{"userId":"RECIPIENT_UID"}`. Request `/calls/token` with the same token
   and `{"callId":"CALL_ID"}`. This checks API authorization and token
   generation; the Android emulator reaches the local API at
   `http://10.0.2.2:3000` (`VOIP_API_BASE_URL`) and connects to the actual
   LiveKit Cloud test project.

FCM delivery requires real Firebase-issued device tokens and cannot be
simulated by the Auth/Realtime Database emulators. The Android notification
tap opens the app with the call ID, caller UID, and call name in its intent;
there is not yet a call UI that accepts or joins directly from that
notification. The emulator-backed API test is API-only: Android's Firebase
Auth SDK is not currently configured to use the Auth emulator. For Android
testing, use a dedicated Firebase test project and ID tokens from that project.

## Android device checklist

- On two Android devices, sign in as the two test users, grant microphone and
  notification permissions, start a call, and accept it on the other device.
- Confirm each device hears the other, mute stops microphone audio, and
  leaving disconnects the LiveKit room.
- Repeat with Wi-Fi/cellular switching and temporary network loss, and note
  whether LiveKit reconnects as expected.
- Test notification tap with the app foregrounded and backgrounded, and verify
  a signed-out device does not show call details.
- Background microphone continuity is not guaranteed: the app does not yet
  run a foreground audio-call service. Real device/network/LiveKit credentials
  are required for these checks.

Realtime Database records should be created and updated only through trusted
server paths or restrictive security rules. The API checks Firebase identity
against the stored caller and participant records before returning credentials.
