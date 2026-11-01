# VoIP library

This package connects to a Mumble server's management interface using ZeroC Ice
and provides an HTTP API for managing calls.

Run the package commands below from the `voip-library` directory.

## Run the unit tests

From this directory:

```bash
npm install
npm test
```

## Test against an isolated local Mumble server

The integration test creates a uniquely named channel on the selected server
and removes it afterward. It is not run by `npm test`.

### Running on Unix

The local test server uses the latest stable release available when this
configuration was added, pinned to Mumble `v1.5.939`. Docker keeps the server
image; its persistent database and configuration data are stored in the ignored
`mumble-data/` directory.

1. Start Docker if its daemon is not already running:

   ```bash
   sudo systemctl start docker
   ```

2. From this directory, start the Mumble server:

   ```bash
   mkdir -p mumble-data
   docker run -d --name voip-library-mumble-test \
     --restart unless-stopped \
     -e 'MUMBLE_CONFIG_ICE=tcp -h 0.0.0.0 -p 6502' \
     -p 127.0.0.1:64738:64738/tcp \
     -p 127.0.0.1:64738:64738/udp \
     -p 127.0.0.1:6502:6502/tcp \
     -v "$PWD/mumble-data:/data" \
     mumblevoip/mumble-server:v1.5.939
   ```

   The voice and Ice ports are published only on localhost. This local test
   configuration does not set an Ice write secret, so do not publish the Ice
   port beyond localhost. If Docker reports a permission error, make sure your
   user has Docker access and log out and back in after being added to the
   `docker` group.

   Check its status with `docker ps --filter name=voip-library-mumble-test`.
   Stop and restart it with:

   ```bash
   docker stop voip-library-mumble-test
   docker start voip-library-mumble-test
   ```

3. The integration test needs the Ice interface definition from the same
   Mumble release as the server. It is stored at
   `mumble/v1.5.939/MumbleServer.ice`; its matching BSD license is alongside it.
   The generated JavaScript bindings go in the ignored `generated/` directory.

   Generate the bindings and run the test:

   ```bash
   npm install
   npm run test:integration
   ```

   This package uses Node.js `22.16.0` or newer and its installed ZeroC Ice
   runtime to generate and load the binding. The integration-test command
   automatically runs `npm run generate:mumble` first.

The test defaults to `127.0.0.1:6502` and virtual server ID `1`. Optional
overrides are `MUMBLE_ICE_HOST`, `MUMBLE_ICE_PORT`, and `MUMBLE_SERVER_ID`.
The generated binding uses the ZeroC Ice version installed by this package.

The test removes its own channel even if its assertions fail. Stopping the
container retains its database in `mumble-data/`.

## HTTP API

The server provides these bearer-token-protected routes:

- `GET /api/calls` lists calls held by this process.
- `POST /api/calls` creates a Mumble channel and invitations. The JSON body
  requires `name`, `callerId`, and a non-empty `participantIds` array.
- `GET /api/calls/invitations?userId=...` returns pending invitations for a user.
- `POST /api/calls/:callId/accept` and `/decline` take a JSON `userId` and
  record that participant's response.
- `DELETE /api/calls/:callId` removes the call channel and the call record.
- `GET /health` checks the HTTP process only; it does not confirm Mumble health.

The API currently stores call and invitation state in memory. Restarting the
process loses that state, and invitations are discovered by client polling
rather than push notifications. The app client polls every five seconds by
default; production notifications require a push delivery service. Call
acceptance records the response but does not yet map an application account to
a Mumble session or move that session into the call channel.

The API requires `VOIP_API_TOKEN` for API routes. The Android client does not
embed a shared server token; its optional bearer-token provider is an
integration point for user authentication. Do not ship the shared API token in
the app. Before live integration, the hosted service needs user-scoped
authentication and authorization for caller and participant IDs.

On Android, `VoipSessionController` composes call signaling with the
`MumbleAudioSession` interface. This is an integration boundary, not a working
Mumble client: no concrete audio session is wired into the app yet. The future
implementation must connect to the voice host using the returned call's
`channelId`, capture and play audio, handle Mumble's voice protocol and
reconnection, and implement microphone mute. The API must provide the voice
endpoint and user-scoped Mumble identity/credentials securely; do not put
server credentials in the app. Android runtime microphone permission and a
microphone foreground service are also required for background calling.

## Run the local HTTP API

Start the Mumble container first, then open a second terminal. These commands
must be run from this package directory. The token below is only a temporary
local test value, not a production credential:

```bash
cd /home/lars/AndroidStudioProjects/DavidsDeals/website/voip-library
export MUMBLE_SLICE_MODULE="$PWD/generated/MumbleServer.js"
export VOIP_API_TOKEN='local-test-token'
npm start
```

In another terminal, confirm the listener and list calls:

```bash
curl -i http://127.0.0.1:3000/health
curl -i -H 'Authorization: Bearer local-test-token' \
  http://127.0.0.1:3000/api/calls
```

The HTTP server listens on localhost port `3000` by default. Stop it with
Ctrl+C. For hosting, place it behind an HTTPS reverse proxy and configure the
proxy to route requests to this service; do not expose the Mumble Ice
management port publicly.
