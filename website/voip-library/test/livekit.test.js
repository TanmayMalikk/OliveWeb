'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createLiveKitTokenIssuer } = require('../src/livekit');

test('LiveKit token is scoped to the requested identity and room', async () => {
    const issuer = createLiveKitTokenIssuer({
        serverUrl: 'wss://livekit.example.test',
        apiKey: 'test-api-key',
        apiSecret: 'test-api-secret',
        ttl: '5m'
    });

    const details = await issuer.issueToken('firebase-user-1', 'call-room-1');
    const payload = JSON.parse(Buffer.from(details.token.split('.')[1], 'base64url').toString());

    assert.equal(details.serverUrl, 'wss://livekit.example.test');
    assert.equal(details.roomName, 'call-room-1');
    assert.equal(payload.iss, 'test-api-key');
    assert.equal(payload.sub, 'firebase-user-1');
    assert.equal(payload.video.room, 'call-room-1');
    assert.equal(payload.video.roomJoin, true);
    assert.equal(payload.video.canPublish, true);
    assert.equal(payload.video.canSubscribe, true);
});

test('LiveKit token issuer requires server credentials', () => {
    assert.throws(
        () => createLiveKitTokenIssuer({ serverUrl: 'wss://livekit.example.test' }),
        /serverUrl, apiKey, and apiSecret are required/
    );
});
