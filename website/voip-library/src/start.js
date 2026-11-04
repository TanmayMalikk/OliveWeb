'use strict';

const { initializeFirebaseAdapters } = require('./firebase');
const { createLiveKitTokenIssuer } = require('./livekit');
const { createApiServer } = require('./server');
const { configuredPort } = require('./config');

function listen(server, port, host) {
    return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => {
            server.off('error', reject);
            resolve();
        });
    });
}

function closeHttpServer(server) {
    return new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    });
}

async function main() {
    const port = configuredPort();

    const firebase = initializeFirebaseAdapters();
    const liveKit = createLiveKitTokenIssuer({
        serverUrl: process.env.LIVEKIT_URL,
        apiKey: process.env.LIVEKIT_API_KEY,
        apiSecret: process.env.LIVEKIT_API_SECRET
    });
    const apiServer = createApiServer({
        callStore: firebase.callStore,
        verifyIdToken: firebase.verifyIdToken,
        verifyUserIds: firebase.verifyUserIds,
        liveKit,
        sendInvitation: firebase.sendInvitation
    });

    await listen(apiServer, port, process.env.VOIP_API_HOST || '0.0.0.0');

    console.log(`VoIP HTTP API listening on ${process.env.VOIP_API_HOST || '0.0.0.0'}:${port}`);

    let shutdownPromise;
    function shutdown() {
        if (!shutdownPromise) {
            shutdownPromise = closeHttpServer(apiServer);
        }
        return shutdownPromise;
    }

    for (const signal of ['SIGINT', 'SIGTERM']) {
        process.once(signal, () => {
            shutdown().catch((error) => {
                console.error('Could not shut down cleanly:', error);
                process.exitCode = 1;
            });
        });
    }
}

// Hostinger loads this file as a module, so startup cannot be hidden behind require.main.
main().catch((error) => {
    console.error('Could not start VoIP API:', error.message);
    process.exitCode = 1;
});
