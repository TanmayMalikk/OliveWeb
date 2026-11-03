'use strict';

const { AccessToken } = require('livekit-server-sdk');

function createLiveKitTokenIssuer({ serverUrl, apiKey, apiSecret, ttl = '1h' }) {
    if (!serverUrl || !apiKey || !apiSecret) {
        throw new TypeError('serverUrl, apiKey, and apiSecret are required');
    }

    return {
        async issueToken(identity, roomName) {
            const token = new AccessToken(apiKey, apiSecret, { identity, ttl });
            token.addGrant({
                roomJoin: true,
                room: roomName,
                canPublish: true,
                canSubscribe: true
            });
            return {
                serverUrl,
                roomName,
                token: await token.toJwt()
            };
        }
    };
}

module.exports = { createLiveKitTokenIssuer };
