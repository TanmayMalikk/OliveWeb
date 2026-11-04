'use strict';

const { randomUUID } = require('node:crypto');

class FirebaseCallStore {
    constructor(database, {
        callsPath = 'calls',
        usersPath = 'users',
        idGenerator = randomUUID,
        clock = Date.now
    } = {}) {
        if (!database) throw new TypeError('database is required');
        this.callsRef = database.ref(callsPath);
        this.usersRef = database.ref(usersPath);
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    async getCall(callId) {
        const snapshot = await this.callsRef.child(callId).once('value');
        return snapshot.val();
    }

    async createCall(name, { callerId, participantIds }) {
        const callId = this.idGenerator();
        const call = {
            callId,
            name,
            callerId,
            status: 'active',
            participants: Object.fromEntries(
                participantIds.map((userId) => [userId, { status: 'pending' }])
            ),
            createdAt: this.clock()
        };
        await this.callsRef.child(callId).set(call);
        return call;
    }

    async listCallsFor(userId) {
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        return Object.values(calls).filter((call) =>
            call?.status === 'active' &&
            (call.callerId === userId || getParticipantStatus(call, userId) === 'accepted')
        );
    }

    async listInvitations(userId) {
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        return Object.values(calls)
            .filter((call) =>
                call?.status === 'active' && getParticipantStatus(call, userId) === 'pending'
            )
            .map((call) => ({
                callId: call.callId,
                callerId: call.callerId,
                name: call.name,
                status: 'pending'
            }));
    }

    async respondToInvitation(callId, userId, accepted) {
        const result = await this.callsRef.child(callId).transaction((call) => {
            if (
                !call ||
                call.status !== 'active' ||
                getParticipantStatus(call, userId) !== 'pending'
            ) {
                return;
            }
            return {
                ...call,
                participants: {
                    ...call.participants,
                    [userId]: { status: accepted ? 'accepted' : 'declined' }
                }
            };
        });
        return result.committed ? result.snapshot.val() : null;
    }

    async endCall(callId, endedAt = Date.now()) {
        const result = await this.callsRef.child(callId).transaction((call) => {
            if (!call || call.status !== 'active') return;
            return { ...call, status: 'ended', endedAt };
        });
        return result.committed;
    }

    async getFcmTokens(userId) {
        const snapshot = await this.usersRef.child(userId).child('fcmTokens').once('value');
        const value = snapshot.val();
        if (Array.isArray(value)) {
            return value.filter((token) => typeof token === 'string' && token.trim().length > 0);
        }
        if (value && typeof value === 'object') {
            return Object.values(value).filter((token) =>
                typeof token === 'string' && token.trim().length > 0
            );
        }
        return [];
    }

    async registerFcmToken(userId, deviceId, token) {
        await this.usersRef.child(userId).child('fcmTokens').child(deviceId).set(token);
    }

    async unregisterFcmToken(userId, deviceId) {
        await this.usersRef.child(userId).child('fcmTokens').child(deviceId).remove();
    }
}

function getParticipantStatus(call, userId) {
    if (call.callerId === userId) return 'accepted';
    const participant = call.participants?.[userId];
    if (typeof participant === 'string') return participant;
    return participant?.status;
}

module.exports = { FirebaseCallStore, getParticipantStatus };
