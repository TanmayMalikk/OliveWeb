'use strict';

const { AccessToken, RoomServiceClient } = require('livekit-server-sdk');

function createLiveKitTokenIssuer({ serverUrl, apiKey, apiSecret, ttl = '1h', roomService }) {
    if (!serverUrl || !apiKey || !apiSecret) {
        throw new TypeError('serverUrl, apiKey, and apiSecret are required');
    }

    const rooms = roomService || new RoomServiceClient(
        serverUrl.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:'),
        apiKey,
        apiSecret
    );
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
        },
        async endRoom(roomName) {
            await rooms.deleteRoom(roomName);
        }
    };
}

module.exports = { createLiveKitTokenIssuer };
