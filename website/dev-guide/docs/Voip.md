# VoIP architecture

```mermaid
flowchart LR
    Android["Android app<br/>VoipManager + LiveKitAudioSession"]
    Api["VoIP API<br/>website/voip-library"]
    FirebaseAuth["Firebase Authentication"]
    Database[("Firebase Realtime Database")]
    FCM["Firebase Cloud Messaging"]
    LiveKit["LiveKit Cloud"]

    Android -- "Firebase ID token; create/respond/end calls" --> Api
    Api -- verify identity --> FirebaseAuth
    Api -- "call membership and status" --> Database
    Api -- "send invitations" --> FCM
    Api -- "create room-scoped access token" --> LiveKit
    Android -- "connect using returned URL and token" --> LiveKit
    FCM -- incoming call notification --> Android
```

The Android client authenticates API requests with the current Firebase user's
ID token. The API validates identity and call membership, stores signaling
state in Firebase Realtime Database, sends invitations through Firebase Cloud
Messaging, and signs LiveKit access tokens on the server. LiveKit API secrets
must remain in the server's secret configuration.

The API implementation and configuration are documented in
[the VoIP library guide](../../voip-library/readme.md). Configure
`VOIP_API_BASE_URL` in the Android project's local build properties to point
to the deployed HTTPS API origin; do not use a client-side shared API secret.
