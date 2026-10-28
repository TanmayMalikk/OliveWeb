'use strict';

const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { MumbleHandler, connectMumbleIce } = require('./index');
const { createApiServer } = require('./server');

async function start() {
    const bindingPath = process.env.MUMBLE_SLICE_MODULE;

    if (!bindingPath) {
        throw new Error('Set MUMBLE_SLICE_MODULE to the generated Mumble bindings file');
    }

    const [{ Ice }, { MumbleServer }] = await Promise.all([
        import('@zeroc/ice'),
        import(pathToFileURL(path.resolve(bindingPath)).href)
    ]);

    const connection = await connectMumbleIce({
        Ice,
        MumbleServer,
        host: process.env.MUMBLE_ICE_HOST || '127.0.0.1',
        port: Number(process.env.MUMBLE_ICE_PORT || 6502),
        serverId: Number(process.env.MUMBLE_SERVER_ID || 1)
    });

    const handler = new MumbleHandler(connection.mumbleServer);

    return {
        handler,
        close: connection.close
    };
}

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
    const apiToken = process.env.VOIP_API_TOKEN;
    if (!apiToken) {
        throw new Error('Set VOIP_API_TOKEN before starting the API');
    }

    const port = Number(process.env.VOIP_API_PORT || 3000);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error('VOIP_API_PORT must be a valid port number');
    }

    const { handler, close: closeMumble } = await start();
    const apiServer = createApiServer({
        callManager: handler,
        apiToken
    });

    try {
        await listen(apiServer, port, '127.0.0.1');
    } catch (error) {
        await closeMumble();
        throw error;
    }

    console.log(`VoIP HTTP API listening at http://127.0.0.1:${port}`);

    let shutdownPromise;
    function shutdown() {
        if (!shutdownPromise) {
            shutdownPromise = (async () => {
                try {
                    await closeHttpServer(apiServer);
                } finally {
                    await closeMumble();
                }
            })();
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

if (require.main === module) {
    main().catch((error) => {
        console.error('Could not start VoIP API:', error.message);
        process.exitCode = 1;
    });
}
module.exports = { start };