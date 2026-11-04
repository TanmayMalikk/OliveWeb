'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createFirebaseAdapters } = require('../src/firebase');

function fakeDatabase(values) {
    function reference(path) {
        return {
            child(key) {
                return reference(`${path}/${key}`);
            },
            async once() {
                return { val: () => values[path] ?? null };
            },
            async transaction(update) {
                const next = update(values[path] ?? null);
                if (next === undefined) return { committed: false };
                values[path] = next;
                return { committed: true };
            }
        };
    }

    return { ref: reference };
}

test('Firebase adapters verify Firebase ID tokens and map invalid-token errors to unauthorized', async () => {
    const adapters = createFirebaseAdapters({
        database: fakeDatabase({}),
        auth: {
            async verifyIdToken(token, checkRevoked) {
                assert.equal(checkRevoked, true);
                if (token === 'expired') {
                    const error = new Error('expired');
                    error.code = 'auth/id-token-expired';
                    throw error;
                }
                if (token === 'firebase-offline') throw new Error('Firebase unavailable');
                return { uid: token };
            }
        },
        messaging: {}
    });

    assert.deepEqual(await adapters.verifyIdToken('alice'), { uid: 'alice' });
    assert.equal(await adapters.verifyIdToken('expired'), null);
    await assert.rejects(adapters.verifyIdToken('firebase-offline'), /Firebase unavailable/);
});

test('Firebase adapters reject missing invitee accounts', async () => {
    const adapters = createFirebaseAdapters({
        database: fakeDatabase({}),
        auth: {
            async verifyIdToken() { return { uid: 'alice' }; },
            async getUser(uid) {
                if (uid === 'missing') {
                    const error = new Error('not found');
                    error.code = 'auth/user-not-found';
                    throw error;
                }
                return { uid };
            }
        },
        messaging: {}
    });

    assert.equal(await adapters.verifyUserIds(['bob']), true);
    assert.equal(await adapters.verifyUserIds(['bob', 'missing']), false);
});

test('Firebase adapters send call invitations to registered recipient tokens', async () => {
    const sentMessages = [];
    const adapters = createFirebaseAdapters({
        database: fakeDatabase({
            'users/carol/fcmTokens': { phone: 'fcm-phone', tablet: 'fcm-tablet' }
        }),
        auth: { async verifyIdToken() { return { uid: 'alice' }; } },
        messaging: {
            async sendEachForMulticast(message) {
                sentMessages.push(message);
                return { successCount: 2, failureCount: 0 };
            }
        }
    });

    const result = await adapters.sendInvitation({
        recipientId: 'carol',
        callId: 'call-1',
        callerId: 'alice',
        callName: 'Support'
    });

    assert.deepEqual(result, { sent: 2, failed: 0 });
    assert.deepEqual(sentMessages, [{
        tokens: ['fcm-phone', 'fcm-tablet'],
        data: {
            type: 'call_invitation',
            callId: 'call-1',
            callerId: 'alice',
            recipientId: 'carol',
            callName: 'Support'
        }
    }]);
});

test('Firebase adapters report missing notification registrations', async () => {
    const adapters = createFirebaseAdapters({
        database: fakeDatabase({}),
        auth: { async verifyIdToken() { return { uid: 'alice' }; } },
        messaging: { async sendEachForMulticast() { throw new Error('Must not send'); } }
    });

    await assert.rejects(
        adapters.sendInvitation({
            recipientId: 'carol',
            callId: 'call-1',
            callerId: 'alice'
        }),
        { code: 'no_fcm_tokens' }
    );
});
