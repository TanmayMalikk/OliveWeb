'use strict';

const http = require('node:http');
const { timingSafeEqual } = require('node:crypto');

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

function hasValidToken(request, expectedToken) {
    const header = request.headers.authorization || '';
    const match = header.match(/^Bearer ([^\s]+)$/i);
    if (!match) return false;

    const supplied = Buffer.from(match[1]);
    const expected = Buffer.from(expectedToken);
    return supplied.length === expected.length && timingSafeEqual(supplied, expected);
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

    try {
        return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
        throw new ApiError(400, 'invalid_json', 'Request body must contain valid JSON');
    }
}

function requireIdentifier(value, fieldName) {
    if (typeof value !== 'string' || value.trim().length === 0) {
        throw new ApiError(400, 'invalid_request', `${fieldName} must be a non-empty string`);
    }
    return value.trim();
}

function createApiServer({ callManager, apiToken, logger = console }) {
    if (!callManager) throw new TypeError('callManager is required');
    if (typeof apiToken !== 'string' || apiToken.length === 0) {
        throw new TypeError('apiToken must be a non-empty string');
    }

    return http.createServer(async (request, response) => {
        try {
            const url = new URL(request.url, 'http://localhost');
            if (request.method === 'GET' && url.pathname === '/health') {
                return sendJson(response, 200, { status: 'ok' });
            }

            if (!hasValidToken(request, apiToken)) {
                return sendJson(response, 401, {
                    error: {
                        code: 'unauthorized',
                        message: 'A valid bearer token is required'
                    }
                });
            }

            if (request.method === 'GET' && url.pathname === '/api/calls') {
                return sendJson(response, 200, { calls: callManager.listCalls() });
            }

            if (request.method === 'GET' && url.pathname === '/api/calls/invitations') {
                const userId = requireIdentifier(url.searchParams.get('userId'), 'userId');
                return sendJson(response, 200, {
                    invitations: callManager.listInvitations(userId)
                });
            }

            if (request.method === 'POST' && url.pathname === '/api/calls') {
                const body = await readJson(request);
                const name = requireIdentifier(body?.name, 'name');
                const callerId = requireIdentifier(body?.callerId, 'callerId');
                if (!Array.isArray(body?.participantIds)) {
                    throw new ApiError(400, 'invalid_request', 'participantIds must be an array');
                }

                const participantIds = body.participantIds.map((id) =>
                    requireIdentifier(id, 'participantIds entry')
                );
                if (participantIds.length === 0) {
                    throw new ApiError(400, 'invalid_request', 'At least one participant is required');
                }
                if (new Set(participantIds).size !== participantIds.length) {
                    throw new ApiError(400, 'invalid_request', 'participantIds must be unique');
                }
                if (participantIds.includes(callerId)) {
                    throw new ApiError(400, 'invalid_request', 'callerId cannot be a participant');
                }

                const call = await callManager.createCall(name, { callerId, participantIds });
                return sendJson(response, 201, call);
            }

            const invitationMatch = url.pathname.match(/^\/api\/calls\/([^/]+)\/(accept|decline)$/);
            if (request.method === 'POST' && invitationMatch) {
                const callId = decodeURIComponent(invitationMatch[1]);
                const body = await readJson(request);
                const userId = requireIdentifier(body?.userId, 'userId');
                const accepted = invitationMatch[2] === 'accept';
                const call = await callManager.respondToInvitation(callId, userId, accepted);
                if (!call) {
                    return sendJson(response, 409, {
                        error: {
                            code: 'invitation_unavailable',
                            message: 'The invitation is no longer available to this user'
                        }
                    });
                }
                return sendJson(response, 200, call);
            }

            const callMatch = url.pathname.match(/^\/api\/calls\/([^/]+)$/);
            if (request.method === 'DELETE' && callMatch) {
                const callId = decodeURIComponent(callMatch[1]);
                const deleted = await callManager.closeCall(callId);
                if (!deleted) {
                    return sendJson(response, 404, {
                        error: { code: 'call_not_found', message: 'Call not found' }
                    });
                }
                return sendJson(response, 200, { deleted: true, callId });
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

            logger.error('API request failed:', error.message);
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
