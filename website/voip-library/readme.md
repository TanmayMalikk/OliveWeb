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

1. Start Docker if its daemon is not already running:

   ```bash
   sudo systemctl start docker
   ```

2. In a terminal, run a temporary Mumble server. Its voice and Ice ports are
   published only on localhost, and `--rm` removes the container when stopped:

   ```bash
   docker run --rm --name voip-library-mumble-test \
     -e 'MUMBLE_CONFIG_ice=tcp -h 0.0.0.0 -p 6502' \
     -p 127.0.0.1:64738:64738/tcp \
     -p 127.0.0.1:64738:64738/udp \
     -p 127.0.0.1:6502:6502/tcp \
     mumblevoip/mumble-server:v1.4.230
   ```

   Keep this terminal open while testing. The Ice listener is reachable only
   through the host's loopback address. This local test configuration does not
   set an Ice write secret, so do not publish the Ice port beyond localhost.

3. On Arch Linux, install the ZeroC Ice 3.7 compiler:

   ```bash
   sudo pacman -S zeroc-ice
   ```

   Obtain `MumbleServer.ice` from the Mumble `v1.4.230` source release to match
   the server image. Generate the JavaScript bindings; if the Slice compiler
   cannot find `Ice/SliceChecksumDict.ice`, provide Ice's Slice include
   directory with `-I`:

   ```bash
   mkdir -p generated
   slice2js --output-dir generated /path/to/MumbleServer.ice
   ```

4. In a second terminal, install the package dependencies if needed and run
   the integration test. Set the binding path to the generated file:

   ```bash
   npm install
   MUMBLE_SLICE_MODULE="$PWD/generated/MumbleServer.js" npm run test:integration
   ```

The test defaults to `127.0.0.1:6502` and virtual server ID `1`. Optional
overrides are `MUMBLE_ICE_HOST`, `MUMBLE_ICE_PORT`, and `MUMBLE_SERVER_ID`.
The generated binding should use the ZeroC Ice version declared by this
package (`@zeroc/ice` 3.7).

Stop the server with Ctrl+C in its terminal. The test removes its own channel
even if its assertions fail.
