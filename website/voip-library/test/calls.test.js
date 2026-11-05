'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { FirebaseCallStore, getParticipantStatus } = require('../src/calls');

function fakeDatabase(values, { initialTransactionNull = false } = {}) {
    function reference(path) {
        return {
            child(key) {
                return reference(`${path}/${key}`);
            },
            async once() {
                const nestedValues = Object.fromEntries(
                    Object.entries(values)
                        .filter(([key]) => key.startsWith(`${path}/`))
                        .map(([key, value]) => [key.slice(path.length + 1), value])
                );
                return {
                    val: () => values[path] ?? (
                        Object.keys(nestedValues).length > 0 ? nestedValues : null
                    )
                };
            },
            async set(value) {
                values[path] = value;
            },
            async remove() {
                delete values[path];
            },
            async transaction(update) {
                const next = update(initialTransactionNull ? null : (values[path] ?? null));
                if (next === undefined) return { committed: false };
                values[path] = next;
                return { committed: true, snapshot: { val: () => next } };
            }
        };
    }

    return { ref: reference };
}

test('FirebaseCallStore reads call records and marks active calls ended', async () => {
    const values = {
        'calls/call-1': { status: 'active', callerId: 'alice' }
    };
    const store = new FirebaseCallStore(fakeDatabase(values));

    assert.deepEqual(await store.getCall('call-1'), values['calls/call-1']);
    assert.equal(await store.endCall('call-1', 'alice', 123), true);
    assert.deepEqual(values['calls/call-1'], {
        status: 'ended',
        callerId: 'alice',
        endedAt: 123,
        endedBy: 'alice',
        endReason: 'ended'
    });
    assert.equal(await store.endCall('call-1', 'alice', 456), false);
});

test('FirebaseCallStore creates calls and tracks invitation responses', async () => {
    const values = {};
    const store = new FirebaseCallStore(fakeDatabase(values), {
        idGenerator: () => 'call-1',
        clock: () => 123
    });

    const call = await store.createCall('Support', {
        callerId: 'alice',
        callerName: 'Alice',
        conversationId: 'conversation-1',
        participantIds: ['bob', 'carol']
    });
    assert.deepEqual(call, {
        callId: 'call-1',
        name: 'Support',
        callerId: 'alice',
        callerName: 'Alice',
        conversationId: 'conversation-1',
        status: 'active',
        participants: {
            bob: { status: 'pending' },
            carol: { status: 'pending' }
        },
        createdAt: 123,
        expiresAt: 20_123
    });
    assert.equal(values['callIdsByUser/alice/call-1'], 123);
    assert.equal(values['callIdsByUser/bob/call-1'], 123);
    assert.equal(values['callIdsByUser/carol/call-1'], 123);
    assert.deepEqual(await store.listInvitations('bob'), [{
        callId: 'call-1',
        callerId: 'alice',
        name: 'Alice',
        status: 'pending',
        expiresAt: 20_123
    }]);
    assert.equal((await store.listCallsFor('bob')).length, 1);

    const accepted = await store.respondToInvitation('call-1', 'bob', true);
    assert.equal(getParticipantStatus(accepted, 'bob'), 'accepted');
    assert.equal((await store.listCallsFor('bob')).length, 1);
    assert.equal(await store.respondToInvitation('call-1', 'bob', false), null);
});

test('FirebaseCallStore handles a transient null on the first transaction callback', async () => {
    const values = {
        'calls/call-1': {
            callId: 'call-1',
            callerId: 'alice',
            callerName: 'Alice',
            name: 'Bob',
            conversationId: 'conversation-1',
            status: 'active',
            participants: { bob: { status: 'pending' } },
            createdAt: 1_000,
            expiresAt: 21_000
        }
    };
    const store = new FirebaseCallStore(
        fakeDatabase(values, { initialTransactionNull: true }),
        { clock: () => 5_000 }
    );

    const accepted = await store.respondToInvitation('call-1', 'bob', true);
    assert.equal(getParticipantStatus(accepted, 'bob'), 'accepted');
    assert.equal(accepted.acceptedAt, 5_000);
    assert.equal(await store.endCall('call-1', 'alice', 6_000), true);
    assert.equal((await store.getCall('call-1')).status, 'ended');
});

test('FirebaseCallStore expires unanswered calls after twenty seconds and records declines', async () => {
    const values = {};
    let now = 1_000;
    const store = new FirebaseCallStore(fakeDatabase(values), {
        idGenerator: () => 'call-1',
        clock: () => now
    });
    await store.createCall('Bob', {
        callerId: 'alice',
        callerName: 'Alice',
        conversationId: 'conversation-1',
        participantIds: ['bob']
    });

    now = 20_999;
    assert.deepEqual(await store.expirePendingCalls(), []);
    now = 21_000;
    assert.deepEqual(await store.expirePendingCalls(), ['call-1']);
    assert.equal((await store.getCall('call-1')).status, 'missed');
    assert.deepEqual(await store.listInvitations('bob'), []);

    const secondStore = new FirebaseCallStore(fakeDatabase(values), {
        idGenerator: () => 'call-2',
        clock: () => 30_000
    });
    await secondStore.createCall('Bob', {
        callerId: 'alice',
        callerName: 'Alice',
        conversationId: 'conversation-1',
        participantIds: ['bob']
    });
    const declined = await secondStore.respondToInvitation('call-2', 'bob', false);
    assert.equal(declined.status, 'declined');
    assert.equal(declined.endReason, 'declined');
});

test('FirebaseCallStore expires legacy pending calls that predate expiresAt', async () => {
    const values = {
        'calls/legacy-call': {
            callId: 'legacy-call',
            callerId: 'alice',
            callerName: 'Alice',
            name: 'Bob',
            conversationId: 'conversation-1',
            status: 'active',
            participants: { bob: { status: 'pending' } },
            createdAt: 1_000
        }
    };
    const store = new FirebaseCallStore(fakeDatabase(values), { clock: () => 50_000 });

    assert.deepEqual(await store.expirePendingCalls(), ['legacy-call']);
    assert.equal((await store.getCall('legacy-call')).status, 'missed');
    assert.equal((await store.getCall('legacy-call')).expiresAt, 21_000);
});

test('FirebaseCallStore reads FCM tokens stored as arrays or maps', async () => {
    const values = {
        'users/alice/fcmTokens': ['device-a', null, 'device-b'],
        'users/bob/fcmTokens': { one: 'device-c', invalid: 42 }
    };
    const store = new FirebaseCallStore(fakeDatabase(values));

    assert.deepEqual(await store.getFcmTokens('alice'), ['device-a', 'device-b']);
    assert.deepEqual(await store.getFcmTokens('bob'), ['device-c']);
    assert.deepEqual(await store.getFcmTokens('missing'), []);
});

test('FirebaseCallStore registers and unregisters a device token under its user', async () => {
    const values = {};
    const store = new FirebaseCallStore(fakeDatabase(values));

    await store.registerFcmToken('alice', 'device-1', 'fcm-token');
    assert.deepEqual(await store.getFcmTokens('alice'), ['fcm-token']);
    await store.unregisterFcmToken('alice', 'device-1');
    assert.deepEqual(await store.getFcmTokens('alice'), []);
});

test('participant status recognizes the caller and explicit participant records', () => {
    const call = {
        callerId: 'alice',
        participants: {
            bob: { status: 'accepted' },
            carol: { status: 'pending' },
            dan: 'declined'
        }
    };

    assert.equal(getParticipantStatus(call, 'alice'), 'accepted');
    assert.equal(getParticipantStatus(call, 'bob'), 'accepted');
    assert.equal(getParticipantStatus(call, 'carol'), 'pending');
    assert.equal(getParticipantStatus(call, 'dan'), 'declined');
    assert.equal(getParticipantStatus(call, 'eve'), undefined);
});
