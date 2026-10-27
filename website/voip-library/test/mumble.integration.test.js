'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { connectMumbleIce } = require('../src/ice-config');
const { MumbleHandler } = require('../src/calls');

test('creates and removes a channel on the local Mumble server', {
    skip: process.env.MUMBLE_INTEGRATION !== '1'
}, async () => {
    const bindingPath = process.env.MUMBLE_SLICE_MODULE;
    assert.ok(bindingPath, 'Set MUMBLE_SLICE_MODULE to the generated MumbleServer.js file');

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
    let call;

    try {
        const name = `voip-library-test-${randomUUID()}`;
        call = await handler.createCall(name);

        const channel = await connection.mumbleServer.getChannelState(call.channelId);
        assert.equal(channel.name, name);
        assert.equal(channel.parent, 0);
    } finally {
        try {
            if (call) assert.equal(await handler.closeCall(call.callId), true);
        } finally {
            await connection.close();
        }
    }
});
