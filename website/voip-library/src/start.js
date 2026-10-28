'use strict';

const path = require('node:path');
const { MumbleHandler, connectMumbleIce } = require('./index');

async function start() {
    const bindingPath = process.env.MUMBLE_SLICE_MODULE;

    if (!bindingPath) {
        throw new Error('Set MUMBLE_SLICE_MODULE to the generated Mumble bindings file');
    }

    const Ice = require('@zeroc/ice');
    const MumbleServer = require(path.resolve(bindingPath));

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

if (require.main === module) {
    start()
        .then(() => console.log('Connected to Mumble ICE'))
        .catch((error) => {
            console.error('Could not start:', error.message);
            process.exitCode = 1;
        });
}

module.exports = { start };