'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createApiServer } = require('../src/server');

async function startTestServer(context, callManager) {
    const server = createApiServer({
        callManager,
        apiToken: 'local-test-token',
        logger: { error() {} }
    });

    server.listen(0, '127.0.0.1');
    await new Promise((resolve) => server.once('listening', resolve));

    context.after(() => new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    }));

    return `http://127.0.0.1:${server.address().port}`;
}

test('GET /health returns healthy without a token', async (context) => {
    const callManager = {
        listCalls() {
            return [];
        },
        async createCall(name) {
            return { name };
        }
    };

    const baseUrl = await startTestServer(context, callManager);
    const response = await fetch(`${baseUrl}/health`);

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'ok' });
});

test('GET /api/calls rejects a request without a bearer token', async (context) => {
    const callManager = {
        listCalls() {
            return [];
        }
    };

    const baseUrl = await startTestServer(context, callManager);
    const response = await fetch(`${baseUrl}/api/calls`);

    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
        error: {
            code: 'unauthorized',
            message: 'A valid bearer token is required'
        }
    });
});

test('GET /api/calls returns calls when the token is valid', async (context) => {
    const expectedCalls = [
        { callId: 'call-1', name: 'Support' },
        { callId: 'call-2', name: 'General' }
    ];

    const callManager = {
        listCalls() {
            return expectedCalls;
        }
    };

    const baseUrl = await startTestServer(context, callManager);
    const response = await fetch(`${baseUrl}/api/calls`, {
        headers: { authorization: 'Bearer local-test-token' }
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { calls: expectedCalls });
});

test('POST /api/calls passes the name to the manager', async (context) => {
    let receivedName;

    const callManager = {
        listCalls() {
            return [];
        },
        async createCall(name) {
            receivedName = name;
            return { callId: 'new-call', name };
        }
    };

    const baseUrl = await startTestServer(context, callManager);
    const response = await fetch(`${baseUrl}/api/calls`, {
        method: 'POST',
        headers: {
            authorization: 'Bearer local-test-token',
            'content-type': 'application/json'
        },
        body: JSON.stringify({ name: '  Support  ' })
    });

    assert.equal(response.status, 201);
    assert.equal(receivedName, 'Support');
    assert.deepEqual(await response.json(), {
        callId: 'new-call',
        name: 'Support'
    });
});