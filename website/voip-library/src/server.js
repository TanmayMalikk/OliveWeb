'use strict';

const http = require('node:http');
const { getParticipantStatus } = require('./calls');

const MAX_BODY_BYTES = 16 * 1024;

class ApiError extends Error {
    constructor(status, code, message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

function sendJson(response, status, data) {
    response.writeHead(status, {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store'
    });
    response.end(JSON.stringify(data));
}

async function readJson(request) {
    const contentType = (request.headers['content-type'] || '')
        .split(';', 1)[0]
        .trim()
        .toLowerCase();
    if (contentType !== 'application/json') {
        throw new ApiError(415, 'unsupported_media_type', 'Content-type must be application/json');
    }

    const chunks = [];
    let totalBytes = 0;
    for await (const chunk of request) {
        totalBytes += chunk.length;
        if (totalBytes > MAX_BODY_BYTES) {
            request.resume();
            throw new ApiError(413, 'payload_too_large', 'Request body is too large');
        }
        chunks.push(chunk);
    }

    let body;
    try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
        throw new ApiError(400, 'invalid_json', 'Request body must contain valid JSON');
    }
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
        throw new ApiError(400, 'invalid_request', 'Request body must be a JSON object');
    }
    return body;
}

function requireIdentifier(value, fieldName) {
    if (typeof value !== 'string' || value.trim().length === 0) {
        throw new ApiError(400, 'invalid_request', `${fieldName} must be a non-empty string`);
    }
    return value.trim();
}

async function authenticate(request, verifyIdToken) {
    const match = (request.headers.authorization || '').match(/^Bearer\s+(\S+)$/i);
    if (!match) {
        throw new ApiError(401, 'unauthorized', 'A valid Firebase bearer token is required');
    }

    const identity = await verifyIdToken(match[1]);
    if (!identity || typeof identity.uid !== 'string' || identity.uid.length === 0) {
        throw new ApiError(401, 'unauthorized', 'A valid Firebase bearer token is required');
    }
    return identity;
}

function requireActiveCall(call) {
    if (!call) throw new ApiError(404, 'call_not_found', 'Call not found');
    if (call.status !== 'active') {
        throw new ApiError(409, 'call_not_active', 'Call is no longer active');
    }
}

function toApiCall(call) {
    return {
        callId: call.callId,
        callerId: call.callerId,
        name: call.name,
        callerName: call.callerName || call.name,
        conversationId: call.conversationId || '',
        status: call.status,
        createdAt: call.createdAt || 0,
        expiresAt: call.expiresAt || 0,
        acceptedAt: call.acceptedAt || 0,
        endedAt: call.endedAt || 0,
        endReason: call.endReason || '',
        participants: [
            { userId: call.callerId, status: 'accepted' },
            ...Object.entries(call.participants || {}).map(([userId, participant]) => ({
                userId,
                status: typeof participant === 'string' ? participant : participant.status
            }))
        ]
    };
}

function decodeCallId(encodedCallId) {
    try {
        return decodeURIComponent(encodedCallId);
    } catch {
        throw new ApiError(400, 'invalid_request', 'Call ID is invalid');
    }
}

function createApiServer({
    callStore,
    verifyIdToken,
    verifyUserIds,
    liveKit,
    sendInvitation,
    logger = console
}) {
    if (!callStore) throw new TypeError('callStore is required');
    if (typeof verifyIdToken !== 'function') throw new TypeError('verifyIdToken is required');
    if (typeof verifyUserIds !== 'function') throw new TypeError('verifyUserIds is required');
    if (!liveKit || typeof liveKit.issueToken !== 'function') {
        throw new TypeError('liveKit.issueToken is required');
    }
    if (typeof sendInvitation !== 'function') throw new TypeError('sendInvitation is required');

    return http.createServer(async (request, response) => {
        try {
            const url = new URL(request.url, 'http://localhost');
            if (request.method === 'GET' && url.pathname === '/health') {
                return sendJson(response, 200, { status: 'ok' });
            }

            const identity = await authenticate(request, verifyIdToken);
            const expireCalls = async () => {
                if (typeof callStore.expirePendingCalls !== 'function') return;
                const expired = await callStore.expirePendingCalls();
                if (typeof liveKit.endRoom === 'function') {
                    await Promise.all(expired.map((callId) =>
                        liveKit.endRoom(`call-${callId}`).catch((error) =>
                            logger.warn?.('Could not close an expired LiveKit room:', error)
                        )
                    ));
                }
            };

            if (request.method === 'POST' && url.pathname === '/auth/verify') {
                return sendJson(response, 200, {
                    uid: identity.uid,
                    ...(identity.email ? { email: identity.email } : {})
                });
            }

            if (request.method === 'GET' && url.pathname === '/api/calls') {
                await expireCalls();
                const calls = await callStore.listCallsFor(identity.uid);
                return sendJson(response, 200, { calls: calls.map(toApiCall) });
            }

            if (request.method === 'POST' && url.pathname === '/api/calls') {
                const body = await readJson(request);
                const callerId = requireIdentifier(body.callerId, 'callerId');
                const name = requireIdentifier(body.name, 'name');
                const callerName = requireIdentifier(body.callerName || body.name, 'callerName');
                const conversationId = requireIdentifier(body.conversationId, 'conversationId');
                if (callerId !== identity.uid) {
                    throw new ApiError(403, 'identity_mismatch', 'callerId must match the authenticated user');
                }
                if (!Array.isArray(body.participantIds) || body.participantIds.length === 0) {
                    throw new ApiError(400, 'invalid_request', 'At least one participant is required');
                }
                const participantIds = body.participantIds.map((userId) =>
                    requireIdentifier(userId, 'participantIds entry')
                );
                if (
                    new Set(participantIds).size !== participantIds.length ||
                    participantIds.includes(identity.uid)
                ) {
                    throw new ApiError(
                        400,
                        'invalid_request',
                        'participantIds must be unique and cannot include the caller'
                    );
                }
                if (!await verifyUserIds(participantIds)) {
                    throw new ApiError(
                        400,
                        'invalid_participant',
                        'Every participant must be an existing Firebase user'
                    );
                }

                const call = await callStore.createCall(name, {
                    callerId: identity.uid,
                    callerName,
                    conversationId,
                    participantIds
                });
                const notificationFailures = [];
                const deliveries = await Promise.allSettled(participantIds.map((recipientId) =>
                    sendInvitation({
                        recipientId,
                        callId: call.callId,
                        callerId: identity.uid,
                        callName: call.callerName || call.name
                    })
                ));
                deliveries.forEach((result, index) => {
                    if (result.status === 'rejected') {
                        notificationFailures.push(participantIds[index]);
                        logger.error('Call invitation delivery failed:', result.reason);
                    } else if (result.value.failed > 0) {
                        notificationFailures.push(participantIds[index]);
                        logger.warn?.(
                            `Call invitation was not delivered to all devices for ${participantIds[index]}`
                        );
                    }
                });
                return sendJson(response, 201, {
                    ...toApiCall(call),
                    notificationFailures
                });
            }

            if (request.method === 'GET' && url.pathname === '/api/calls/invitations') {
                await expireCalls();
                const userId = requireIdentifier(url.searchParams.get('userId'), 'userId');
                if (userId !== identity.uid) {
                    throw new ApiError(403, 'identity_mismatch', 'userId must match the authenticated user');
                }
                return sendJson(response, 200, {
                    invitations: await callStore.listInvitations(identity.uid)
                });
            }

            const invitationMatch = url.pathname.match(/^\/api\/calls\/([^/]+)\/(accept|decline)$/);
            if (request.method === 'POST' && invitationMatch) {
                await expireCalls();
                const callId = decodeCallId(invitationMatch[1]);
                const body = await readJson(request);
                const userId = requireIdentifier(body.userId, 'userId');
                if (userId !== identity.uid) {
                    throw new ApiError(403, 'identity_mismatch', 'userId must match the authenticated user');
                }
                const call = await callStore.respondToInvitation(
                    callId,
                    identity.uid,
                    invitationMatch[2] === 'accept'
                );
                if (!call) {
                    throw new ApiError(
                        409,
                        'invitation_unavailable',
                        'The invitation is no longer available to this user'
                    );
                }
                if (invitationMatch[2] === 'decline' && typeof liveKit.endRoom === 'function') {
                    await liveKit.endRoom(`call-${callId}`).catch((error) =>
                        logger.warn?.('Could not close the declined LiveKit room:', error)
                    );
                }
                return sendJson(response, 200, toApiCall(call));
            }

            if (request.method === 'POST' && url.pathname === '/calls/token') {
                const body = await readJson(request);
                const callId = requireIdentifier(body.callId, 'callId');
                await expireCalls();
                const currentCall = await callStore.getCall(callId);
                requireActiveCall(currentCall);

                if (getParticipantStatus(currentCall, identity.uid) !== 'accepted') {
                    throw new ApiError(403, 'not_a_call_member', 'User is not an accepted call participant');
                }

                const roomName = `call-${callId}`;
                return sendJson(response, 200, {
                    callId,
                    ...(await liveKit.issueToken(identity.uid, roomName))
                });
            }

            const legacyEndMatch = url.pathname.match(/^\/api\/calls\/([^/]+)$/);
            if (
                (request.method === 'POST' && url.pathname === '/calls/end') ||
                (request.method === 'DELETE' && legacyEndMatch)
            ) {
                await expireCalls();
                const callId = request.method === 'DELETE'
                    ? decodeCallId(legacyEndMatch[1])
                    : requireIdentifier((await readJson(request)).callId, 'callId');
                const call = await callStore.getCall(callId);
                requireActiveCall(call);
                if (getParticipantStatus(call, identity.uid) !== 'accepted') {
                    throw new ApiError(403, 'not_call_member', 'Only a call participant can end this call');
                }

                const ended = await callStore.endCall(callId, identity.uid);
                if (!ended) {
                    throw new ApiError(409, 'call_not_active', 'Call is no longer active');
                }
                if (typeof liveKit.endRoom === 'function') {
                    try {
                        await liveKit.endRoom(`call-${callId}`);
                    } catch (error) {
                        logger.warn?.('Could not close the LiveKit room:', error);
                    }
                }
                if (request.method === 'DELETE') {
                    return sendJson(response, 200, { deleted: true, callId });
                }
                return sendJson(response, 200, { callId, status: 'ended' });
            }

            if (request.method === 'POST' && url.pathname === '/notifications/send') {
                await expireCalls();
                const body = await readJson(request);
                const callId = requireIdentifier(body.callId, 'callId');
                const recipientId = requireIdentifier(body.recipientId, 'recipientId');
                const call = await callStore.getCall(callId);
                requireActiveCall(call);
                if (call.callerId !== identity.uid) {
                    throw new ApiError(403, 'not_call_owner', 'Only the call owner can send invitations');
                }
                if (getParticipantStatus(call, recipientId) !== 'pending') {
                    throw new ApiError(403, 'invalid_recipient', 'Recipient is not a pending call participant');
                }

                const delivery = await sendInvitation({
                    recipientId,
                    callId,
                    callerId: identity.uid,
                    callName: call.callerName || call.name,
                    recipientId
                });
                return sendJson(response, 200, { callId, recipientId, delivery });
            }

            if (url.pathname === '/notifications/register') {
                if (request.method === 'POST') {
                    const body = await readJson(request);
                    const deviceId = requireIdentifier(body.deviceId, 'deviceId');
                    const token = requireIdentifier(body.token, 'token');
                    if (!/^[A-Za-z0-9_-]{1,128}$/.test(deviceId)) {
                        throw new ApiError(400, 'invalid_request', 'deviceId contains unsupported characters');
                    }
                    if (token.length > 4096) {
                        throw new ApiError(400, 'invalid_request', 'token is too long');
                    }
                    await callStore.registerFcmToken(identity.uid, deviceId, token);
                    return sendJson(response, 200, { registered: true });
                }

                if (request.method === 'DELETE') {
                    const body = await readJson(request);
                    const deviceId = requireIdentifier(body.deviceId, 'deviceId');
                    if (!/^[A-Za-z0-9_-]{1,128}$/.test(deviceId)) {
                        throw new ApiError(400, 'invalid_request', 'deviceId contains unsupported characters');
                    }
                    await callStore.unregisterFcmToken(identity.uid, deviceId);
                    return sendJson(response, 200, { registered: false });
                }
            }

            return sendJson(response, 404, {
                error: { code: 'not_found', message: 'Route not found' }
            });
        } catch (error) {
            if (error instanceof ApiError) {
                return sendJson(response, error.status, {
                    error: { code: error.code, message: error.message }
                });
            }
            if (error.code === 'no_fcm_tokens') {
                return sendJson(response, 409, {
                    error: {
                        code: 'recipient_unavailable',
                        message: 'Recipient has no registered device for notifications'
                    }
                });
            }

            logger.error('API request failed:', error);
            return sendJson(response, 502, {
                error: {
                    code: 'operation_failed',
                    message: 'The requested operation failed'
                }
            });
        }
    });
}

module.exports = { createApiServer };
