'use strict';

const { randomUUID } = require('node:crypto');

const CALL_TIMEOUT_MS = 20_000;

function callExpiry(call) {
    if (Number.isFinite(call?.expiresAt) && call.expiresAt > 0) return call.expiresAt;
    if (Number.isFinite(call?.createdAt) && call.createdAt > 0) {
        return call.createdAt + CALL_TIMEOUT_MS;
    }
    return 0;
}

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

    async createCall(name, { callerId, callerName, participantIds, conversationId }) {
        const callId = this.idGenerator();
        const createdAt = this.clock();
        const call = {
            callId,
            name,
            callerId,
            callerName: callerName || name,
            conversationId,
            status: 'active',
            participants: Object.fromEntries(
                participantIds.map((userId) => [userId, { status: 'pending' }])
            ),
            createdAt,
            expiresAt: createdAt + CALL_TIMEOUT_MS
        };
        await this.callsRef.child(callId).set(call);
        return call;
    }

    async listCallsFor(userId) {
        await this.expirePendingCalls();
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        return Object.values(calls).filter((call) =>
            call && (call.callerId === userId || getParticipantStatus(call, userId) != null)
        ).sort((left, right) => (right.createdAt || 0) - (left.createdAt || 0));
    }

    async listInvitations(userId) {
        await this.expirePendingCalls();
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        return Object.values(calls)
            .filter((call) =>
                call?.status === 'active' && getParticipantStatus(call, userId) === 'pending'
            )
            .map((call) => ({
                callId: call.callId,
                callerId: call.callerId,
                name: call.callerName || call.name,
                status: 'pending',
                expiresAt: callExpiry(call)
            }));
    }

    async respondToInvitation(callId, userId, accepted) {
        await this.expirePendingCalls();
        const existingCall = await this.getCall(callId);
        const result = await this.callsRef.child(callId).transaction((current) => {
            // Realtime Database may invoke the updater with a transient local null before it
            // receives the server value. Seed that first pass with the snapshot we just read;
            // Firebase will still retry with the authoritative value if it changed remotely.
            const call = current ?? existingCall;
            if (
                !call ||
                call.status !== 'active' ||
                getParticipantStatus(call, userId) !== 'pending'
            ) {
                return;
            }
            const now = this.clock();
            const updated = {
                ...call,
                participants: {
                    ...call.participants,
                    [userId]: { status: accepted ? 'accepted' : 'declined' }
                }
            };
            return accepted
                ? { ...updated, acceptedAt: now }
                : {
                    ...updated,
                    status: 'declined',
                    endedAt: now,
                    endedBy: userId,
                    endReason: 'declined'
                };
        });
        return result.committed ? result.snapshot.val() : null;
    }

    async endCall(callId, endedBy, endedAt = this.clock()) {
        const existingCall = await this.getCall(callId);
        const result = await this.callsRef.child(callId).transaction((current) => {
            const call = current ?? existingCall;
            if (!call || call.status !== 'active') return;
            return { ...call, status: 'ended', endedAt, endedBy, endReason: 'ended' };
        });
        return result.committed;
    }

    async expirePendingCalls(now = this.clock()) {
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        const expired = [];
        await Promise.all(Object.values(calls).map(async (call) => {
            const expiresAt = callExpiry(call);
            if (!call || call.status !== 'active' || call.acceptedAt ||
                !expiresAt || expiresAt > now) return;
            const result = await this.callsRef.child(call.callId).transaction((current) => {
                const candidate = current ?? call;
                const currentExpiry = callExpiry(candidate);
                if (!candidate || candidate.status !== 'active' || candidate.acceptedAt ||
                    !currentExpiry || currentExpiry > now) return;
                return {
                    ...candidate,
                    expiresAt: currentExpiry,
                    status: 'missed',
                    endedAt: now,
                    endReason: 'missed'
                };
            });
            if (result.committed) expired.push(call.callId);
        }));
        return expired;
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

module.exports = { FirebaseCallStore, getParticipantStatus, CALL_TIMEOUT_MS, callExpiry };
