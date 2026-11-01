'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createApiServer } = require('../src/server');
const { MumbleHandler } = require('../src/calls');

async function startTestServer(context, callManager) {
    const server = createApiServer({
        callManager,
        apiToken: 'test-token',
        logger: { error() {} }
    });
    server.listen(0, '127.0.0.1');
    await new Promise((resolve) => server.once('listening', resolve));
    context.after(() => new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    }));
    return `http://127.0.0.1:${server.address().port}`;
}

function authorizedHeaders(extra = {}) {
    return { authorization: 'Bearer test-token', ...extra };
}

test('call participants can receive, accept, decline, and end group calls', async (context) => {
    const mumble = {
        async addChannel() {
            return 42;
        },
        async removeChannel(channelId) {
            assert.equal(channelId, 42);
        }
    };
    const callManager = new MumbleHandler(mumble);
    const baseUrl = await startTestServer(context, callManager);
    const createdResponse = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: authorizedHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify({
            name: 'Support',
            callerId: 'caller-1',
            participantIds: ['user-2', 'user-3']
        })
    });
    assert.equal(createdResponse.status, 201);
    const call = await createdResponse.json();
    assert.equal(call.callerId, 'caller-1');
    assert.deepEqual(call.participants, [
        { userId: 'caller-1', status: 'accepted' },
        { userId: 'user-2', status: 'pending' },
        { userId: 'user-3', status: 'pending' }
    ]);

    const invitationsResponse = await fetch(
        `${baseUrl}/api/calls/invitations?userId=user-2`,
        { headers: authorizedHeaders() }
    );
    assert.equal(invitationsResponse.status, 200);
    assert.deepEqual(await invitationsResponse.json(), {
        invitations: [{
            callId: call.callId,
            callerId: 'caller-1',
            name: 'Support',
            status: 'pending'
        }]
    });

    const acceptedResponse = await fetch(`${baseUrl}/api/calls/${call.callId}/accept`, {
        method: 'POST',
        headers: authorizedHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify({ userId: 'user-2' })
    });
    assert.equal(acceptedResponse.status, 200);
    assert.equal((await acceptedResponse.json()).participants[1].status, 'accepted');

    const declinedResponse = await fetch(`${baseUrl}/api/calls/${call.callId}/decline`, {
        method: 'POST',
        headers: authorizedHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify({ userId: 'user-3' })
    });
    assert.equal(declinedResponse.status, 200);
    assert.equal((await declinedResponse.json()).participants[2].status, 'declined');

    const endedResponse = await fetch(`${baseUrl}/api/calls/${call.callId}`, {
        method: 'DELETE',
        headers: authorizedHeaders()
    });
    assert.equal(endedResponse.status, 200);
    assert.deepEqual(await endedResponse.json(), { deleted: true, callId: call.callId });
});

test('only pending invitees can respond to a call', async (context) => {
    const callManager = new MumbleHandler({
        async addChannel() {
            return 10;
        }
    });
    const call = await callManager.createCall('Support', {
        callerId: 'caller-1',
        participantIds: ['user-2']
    });
    await callManager.respondToInvitation(call.callId, 'user-2', true);
    const baseUrl = await startTestServer(context, callManager);

    const response = await fetch(`${baseUrl}/api/calls/${call.callId}/decline`, {
        method: 'POST',
        headers: authorizedHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify({ userId: 'user-2' })
    });
    assert.equal(response.status, 409);
    assert.equal((await response.json()).error.code, 'invitation_unavailable');
});

test('call creation requires at least one invitee', async (context) => {
    const callManager = new MumbleHandler({
        async addChannel() {
            throw new Error('Should not create a channel');
        }
    });
    const baseUrl = await startTestServer(context, callManager);

    const response = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: authorizedHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify({ name: 'Solo', callerId: 'caller-1', participantIds: [] })
    });
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'invalid_request');
});
