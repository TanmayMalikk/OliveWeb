'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createApiServer } = require('../src/server');

function createDependencies() {
    const calls = new Map([
        ['call-1', {
            callId: 'call-1',
            callerId: 'alice',
            callerName: 'Alice',
            name: 'Support',
            status: 'active',
            participants: {
                bob: { status: 'accepted' },
                carol: { status: 'pending' }
            }
        }]
    ]);
    const invitations = [];
    const endedRooms = [];
    const deviceTokens = new Map();
    const callStore = {
        async getCall(callId) {
            return calls.get(callId) ?? null;
        },
        async listCallsFor(userId) {
            return [...calls.values()].filter((call) =>
                call.callerId === userId || call.participants[userId]?.status != null
            );
        },
        async listInvitations(userId) {
            return [...calls.entries()]
                .filter(([, call]) =>
                    call.status === 'active' && call.participants[userId]?.status === 'pending'
                )
                .map(([callId, call]) => ({
                    callId,
                    callerId: call.callerId,
                    name: call.callerName || call.name,
                    status: 'pending'
                }));
        },
        async createCall(name, { callerId, callerName, conversationId, participantIds }) {
            const call = {
                callId: 'new-call',
                name,
                callerId,
                callerName,
                conversationId,
                status: 'active',
                participants: Object.fromEntries(
                    participantIds.map((id) => [id, { status: 'pending' }])
                )
            };
            calls.set(call.callId, call);
            return call;
        },
        async respondToInvitation(callId, userId, accepted) {
            const call = calls.get(callId);
            if (!call || call.participants[userId]?.status !== 'pending') return null;
            call.participants[userId] = { status: accepted ? 'accepted' : 'declined' };
            return call;
        },
        async leaveCall(callId, userId) {
            const call = calls.get(callId);
            const isCaller = call?.callerId === userId && !call?.callerLeftAt;
            const isAcceptedInvitee = call?.participants[userId]?.status === 'accepted';
            if (!call || call.status !== 'active' || (!isCaller && !isAcceptedInvitee)) return null;
            if (isCaller) call.callerLeftAt = Date.now();
            else call.participants[userId] = { status: 'declined' };
            const acceptedInvitees = Object.values(call.participants)
                .filter(({ status }) => status === 'accepted').length;
            if (call.callerLeftAt && acceptedInvitees === 0) {
                call.status = 'ended';
                call.endReason = 'all_left';
            }
            return call;
        },
        async rejoinCall(callId, userId) {
            const call = calls.get(callId);
            if (!call || call.status !== 'active') return null;
            const connectedCount = (call.callerLeftAt ? 0 : 1) +
                Object.values(call.participants).filter(({ status }) => status === 'accepted').length;
            if (connectedCount < 2) return null;
            if (call.callerId === userId && call.callerLeftAt) delete call.callerLeftAt;
            else if (call.participants[userId]?.status === 'declined') {
                call.participants[userId] = { status: 'accepted' };
            } else return null;
            return call;
        },
        async endCall(callId, endedBy) {
            const call = calls.get(callId);
            if (!call || call.status !== 'active') return false;
            call.status = 'ended';
            call.endedBy = endedBy;
            return true;
        },
        async registerFcmToken(userId, deviceId, token) {
            deviceTokens.set(`${userId}/${deviceId}`, token);
        },
        async unregisterFcmToken(userId, deviceId) {
            deviceTokens.delete(`${userId}/${deviceId}`);
        }
    };

    return {
        calls,
        invitations,
        endedRooms,
        deviceTokens,
        callStore,
        verifyIdToken: async (token) => {
            const identities = {
                'token-alice': { uid: 'alice', email: 'alice@example.test' },
                'token-bob': { uid: 'bob' },
                'token-carol': { uid: 'carol' },
                'token-outsider': { uid: 'outsider' }
            };
            return identities[token] ?? null;
        },
        async verifyUserIds(userIds) {
            return userIds.every((userId) => ['bob', 'carol'].includes(userId));
        },
        liveKit: {
            async issueToken(identity, roomName) {
                return {
                    serverUrl: 'wss://livekit.example.test',
                    roomName,
                    token: `signed-${identity}`
                };
            },
            async endRoom(roomName) {
                endedRooms.push(roomName);
            }
        },
        async sendInvitation(invitation) {
            invitations.push(invitation);
            return { sent: 1, failed: 0 };
        }
    };
}

async function startTestServer(context, dependencies = createDependencies()) {
    const server = createApiServer({
        ...dependencies,
        logger: { error() {} }
    });
    server.listen(0, '127.0.0.1');
    await new Promise((resolve) => server.once('listening', resolve));
    context.after(() => new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    }));
    return `http://127.0.0.1:${server.address().port}`;
}

function authorized(token, extra = {}) {
    return { authorization: `Bearer ${token}`, ...extra };
}

test('GET /health is public', async (context) => {
    const baseUrl = await startTestServer(context);
    const response = await fetch(`${baseUrl}/health`);

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'ok' });
});

test('protected routes reject missing and invalid Firebase tokens', async (context) => {
    const baseUrl = await startTestServer(context);
    for (const headers of [{}, authorized('invalid')]) {
        const response = await fetch(`${baseUrl}/auth/verify`, {
            method: 'POST',
            headers
        });
        assert.equal(response.status, 401);
        assert.equal((await response.json()).error.code, 'unauthorized');
    }
});

test('POST /auth/verify returns verified Firebase identity claims', async (context) => {
    const baseUrl = await startTestServer(context);
    const response = await fetch(`${baseUrl}/auth/verify`, {
        method: 'POST',
        headers: authorized('token-alice')
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
        uid: 'alice',
        email: 'alice@example.test'
    });
});

test('legacy call-signaling routes persist calls and bind user IDs to Firebase identity', async (context) => {
        const dependencies = createDependencies();
        dependencies.calls.clear();
        const baseUrl = await startTestServer(context, dependencies);
        const created = await fetch(`${baseUrl}/api/calls`, {
            method: 'POST',
            headers: authorized('token-alice', { 'content-type': 'application/json' }),
            body: JSON.stringify({
                name: '  Product help ',
                callerId: 'alice',
                callerName: 'Alice',
                conversationId: 'conversation-1',
                participantIds: ['carol']
            })
        });

        assert.equal(created.status, 201);
        assert.deepEqual(await created.json(), {
            callId: 'new-call',
            callerId: 'alice',
            name: 'Product help',
            callerName: 'Alice',
            conversationId: 'conversation-1',
            status: 'active',
            createdAt: 0,
            expiresAt: 0,
            acceptedAt: 0,
            endedAt: 0,
            endReason: '',
            participants: [
                { userId: 'alice', status: 'accepted' },
                { userId: 'carol', status: 'pending' }
            ],
            notificationFailures: []
        });
        assert.deepEqual(dependencies.invitations, [{
            recipientId: 'carol',
            callId: 'new-call',
            callerId: 'alice',
            callName: 'Alice'
        }]);

        const invitations = await fetch(
            `${baseUrl}/api/calls/invitations?userId=carol`,
            { headers: authorized('token-carol') }
        );
        assert.deepEqual(await invitations.json(), {
            invitations: [{
                callId: 'new-call',
                callerId: 'alice',
                name: 'Alice',
                status: 'pending'
            }]
        });

        const activeCalls = await fetch(`${baseUrl}/api/calls`, {
            headers: authorized('token-alice')
        });
        assert.deepEqual((await activeCalls.json()).calls.map(({ callId }) => callId), ['new-call']);

        const forgedIdentity = await fetch(`${baseUrl}/api/calls`, {
            method: 'POST',
            headers: authorized('token-bob', { 'content-type': 'application/json' }),
            body: JSON.stringify({
                name: 'Forged call',
                callerId: 'alice',
                conversationId: 'conversation-1',
                participantIds: ['carol']
            })
        });
        assert.equal(forgedIdentity.status, 403);
        assert.equal((await forgedIdentity.json()).error.code, 'identity_mismatch');
});

test('legacy invitation routes accept and decline only the authenticated pending user', async (context) => {
        const dependencies = createDependencies();
        const baseUrl = await startTestServer(context, dependencies);
        const response = await fetch(`${baseUrl}/api/calls/call-1/accept`, {
            method: 'POST',
            headers: authorized('token-carol', { 'content-type': 'application/json' }),
            body: JSON.stringify({ userId: 'carol' })
        });

        assert.equal(response.status, 200);
        const call = await response.json();
        assert.equal(call.participants.find(({ userId }) => userId === 'carol').status, 'accepted');

        const activeCalls = await fetch(`${baseUrl}/api/calls`, {
            headers: authorized('token-carol')
        });
        assert.deepEqual((await activeCalls.json()).calls.map(({ callId }) => callId), ['call-1']);

        const forged = await fetch(`${baseUrl}/api/calls/call-1/decline`, {
            method: 'POST',
            headers: authorized('token-bob', { 'content-type': 'application/json' }),
            body: JSON.stringify({ userId: 'carol' })
        });
        assert.equal(forged.status, 403);
        assert.equal((await forged.json()).error.code, 'identity_mismatch');

        dependencies.calls.get('call-1').participants.carol = { status: 'pending' };
        dependencies.calls.get('call-1').status = 'active';
        const declined = await fetch(`${baseUrl}/api/calls/call-1/decline`, {
            method: 'POST',
            headers: authorized('token-carol', { 'content-type': 'application/json' }),
            body: JSON.stringify({ userId: 'carol' })
        });
        assert.equal(declined.status, 200);
        assert.equal(dependencies.calls.get('call-1').status, 'active');
        assert.equal(dependencies.calls.get('call-1').participants.carol.status, 'declined');
        assert.deepEqual(dependencies.endedRooms, []);
});

test('call creation rejects participant IDs without Firebase accounts', async (context) => {
    const dependencies = createDependencies();
    const baseUrl = await startTestServer(context, dependencies);
    const response = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: JSON.stringify({
            name: 'Invalid invite',
            callerId: 'alice',
            conversationId: 'conversation-1',
            participantIds: ['missing-user']
        })
    });

    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'invalid_participant');
    assert.equal(dependencies.calls.has('new-call'), false);
});

test('call creation keeps the call and reports failed invitation delivery', async (context) => {
    const dependencies = createDependencies();
    dependencies.sendInvitation = async () => {
        throw new Error('FCM unavailable');
    };
    const baseUrl = await startTestServer(context, dependencies);
    const response = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: JSON.stringify({
            name: 'Delivery check',
            callerId: 'alice',
            conversationId: 'conversation-1',
            participantIds: ['carol']
        })
    });

    assert.equal(response.status, 201);
    const call = await response.json();
    assert.equal(call.callId, 'new-call');
    assert.deepEqual(call.notificationFailures, ['carol']);
    assert.equal(dependencies.calls.has('new-call'), true);
});

test('call creation reports partial delivery to a participant device', async (context) => {
    const dependencies = createDependencies();
    dependencies.sendInvitation = async () => ({ sent: 1, failed: 1 });
    const baseUrl = await startTestServer(context, dependencies);
    const response = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: JSON.stringify({
            name: 'Partial delivery',
            callerId: 'alice',
            conversationId: 'conversation-1',
            participantIds: ['carol']
        })
    });

    assert.equal(response.status, 201);
    assert.deepEqual((await response.json()).notificationFailures, ['carol']);
});

test('POST /calls/token issues a room-scoped token only to accepted participants', async (context) => {
    const baseUrl = await startTestServer(context);
    const response = await fetch(`${baseUrl}/calls/token`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: JSON.stringify({ callId: 'call-1' })
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
        callId: 'call-1',
        serverUrl: 'wss://livekit.example.test',
        roomName: 'call-call-1',
        token: 'signed-bob'
    });

    const pendingResponse = await fetch(`${baseUrl}/calls/token`, {
        method: 'POST',
        headers: authorized('token-carol', { 'content-type': 'application/json' }),
        body: JSON.stringify({ callId: 'call-1' })
    });
    assert.equal(pendingResponse.status, 403);
    assert.equal((await pendingResponse.json()).error.code, 'not_a_call_member');
});

test('POST /calls/token rejects inactive calls and unknown call IDs', async (context) => {
    const dependencies = createDependencies();
    dependencies.calls.set('ended-call', { status: 'ended', callerId: 'alice' });
    const baseUrl = await startTestServer(context, dependencies);
    const options = {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: JSON.stringify({ callId: 'ended-call' })
    };

    const inactive = await fetch(`${baseUrl}/calls/token`, options);
    assert.equal(inactive.status, 409);
    assert.equal((await inactive.json()).error.code, 'call_not_active');

    const missing = await fetch(`${baseUrl}/calls/token`, {
        ...options,
        body: JSON.stringify({ callId: 'missing' })
    });
    assert.equal(missing.status, 404);
    assert.equal((await missing.json()).error.code, 'call_not_found');
});

test('a connected group ends only after every member leaves', async (context) => {
    const dependencies = createDependencies();
    const baseUrl = await startTestServer(context, dependencies);
    const memberEnd = await fetch(`${baseUrl}/calls/end`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: JSON.stringify({ callId: 'call-1' })
    });
    assert.equal(memberEnd.status, 409);
    assert.equal((await memberEnd.json()).error.code, 'group_member_must_leave');

    const left = await fetch(`${baseUrl}/api/calls/call-1/leave`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: '{}'
    });
    assert.equal(left.status, 200);
    assert.equal(dependencies.calls.get('call-1').status, 'active');
    assert.equal(dependencies.calls.get('call-1').participants.bob.status, 'declined');
    assert.deepEqual(dependencies.endedRooms, []);

    dependencies.calls.get('call-1').participants.carol = { status: 'accepted' };
    const rejoined = await fetch(`${baseUrl}/api/calls/call-1/rejoin`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: '{}'
    });
    assert.equal(rejoined.status, 200);
    assert.equal(dependencies.calls.get('call-1').participants.bob.status, 'accepted');
    await fetch(`${baseUrl}/api/calls/call-1/leave`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: '{}'
    });

    const callerLeft = await fetch(`${baseUrl}/api/calls/call-1/leave`, {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: '{}'
    });
    assert.equal(callerLeft.status, 200);
    assert.equal(dependencies.calls.get('call-1').status, 'active');
    assert.deepEqual(dependencies.endedRooms, []);

    const lastMemberLeft = await fetch(`${baseUrl}/api/calls/call-1/leave`, {
        method: 'POST',
        headers: authorized('token-carol', { 'content-type': 'application/json' }),
        body: '{}'
    });
    assert.equal(lastMemberLeft.status, 200);
    assert.equal(dependencies.calls.get('call-1').status, 'ended');
    assert.equal(dependencies.calls.get('call-1').endReason, 'all_left');
    assert.deepEqual(dependencies.endedRooms, ['call-call-1']);
});

test('POST /notifications/send permits the owner to invite only pending participants', async (context) => {
    const dependencies = createDependencies();
    const baseUrl = await startTestServer(context, dependencies);
    const request = (token, recipientId) => fetch(`${baseUrl}/notifications/send`, {
        method: 'POST',
        headers: authorized(token, { 'content-type': 'application/json' }),
        body: JSON.stringify({ callId: 'call-1', recipientId })
    });

    const acceptedParticipant = await request('token-alice', 'bob');
    assert.equal(acceptedParticipant.status, 403);
    assert.equal((await acceptedParticipant.json()).error.code, 'invalid_recipient');

    const sent = await request('token-alice', 'carol');
    assert.equal(sent.status, 200);
    assert.deepEqual(dependencies.invitations, [{
        recipientId: 'carol',
        callId: 'call-1',
        callerId: 'alice',
        callName: 'Alice'
    }]);

    const nonOwner = await request('token-bob', 'carol');
    assert.equal(nonOwner.status, 403);
    assert.equal((await nonOwner.json()).error.code, 'not_call_owner');
});

test('push tokens are registered and removed only for the authenticated Firebase user', async (context) => {
    const dependencies = createDependencies();
    const baseUrl = await startTestServer(context, dependencies);
    const register = await fetch(`${baseUrl}/notifications/register`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: JSON.stringify({ deviceId: 'install-123', token: 'fcm-device-token' })
    });

    assert.equal(register.status, 200);
    assert.deepEqual(await register.json(), { registered: true });
    assert.equal(dependencies.deviceTokens.get('bob/install-123'), 'fcm-device-token');

    const unregister = await fetch(`${baseUrl}/notifications/register`, {
        method: 'DELETE',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: JSON.stringify({ deviceId: 'install-123' })
    });
    assert.equal(unregister.status, 200);
    assert.deepEqual(await unregister.json(), { registered: false });
    assert.equal(dependencies.deviceTokens.has('bob/install-123'), false);

    const invalid = await fetch(`${baseUrl}/notifications/register`, {
        method: 'POST',
        headers: authorized('token-bob', { 'content-type': 'application/json' }),
        body: JSON.stringify({ deviceId: '../alice', token: 'fcm-device-token' })
    });
    assert.equal(invalid.status, 400);
});

test('malformed JSON requests receive a client error', async (context) => {
    const baseUrl = await startTestServer(context);
    const response = await fetch(`${baseUrl}/calls/token`, {
        method: 'POST',
        headers: authorized('token-alice', { 'content-type': 'application/json' }),
        body: '{'
    });

    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'invalid_json');
});
