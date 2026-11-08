'use strict';

const { randomUUID } = require('node:crypto');

const CALL_TIMEOUT_MS = 20_000;
const MAX_INDEXED_CALLS = 100;

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
        callIdsByUserPath = 'callIdsByUser',
        usersPath = 'users',
        idGenerator = randomUUID,
        clock = Date.now
    } = {}) {
        if (!database) throw new TypeError('database is required');
        this.callsRef = database.ref(callsPath);
        this.callIdsByUserRef = database.ref(callIdsByUserPath);
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
        await Promise.all([callerId, ...participantIds].map((userId) =>
            this.callIdsByUserRef.child(userId).child(callId).set(createdAt)
        ));
        return call;
    }

    async listCallsFor(userId) {
        const calls = await this.getIndexedCalls(userId);
        const now = this.clock();
        const currentCalls = await Promise.all(calls.map((call) =>
            this.expireCall(call.callId, now, call)
        ));
        return currentCalls.filter((call) =>
            call && (call.callerId === userId || getParticipantStatus(call, userId) != null)
        ).sort((left, right) => (right.createdAt || 0) - (left.createdAt || 0));
    }

    async listInvitations(userId) {
        const calls = await this.listCallsFor(userId);
        return calls
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
        await this.expireCall(callId);
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
            // One person declining a group invitation must not close the shared room for
            // everyone else. The call stays active so the other invitees can still join.
            return accepted ? { ...updated, acceptedAt: now } : updated;
        });
        return result.committed ? result.snapshot.val() : null;
    }

    async leaveCall(callId, userId) {
        const existingCall = await this.getCall(callId);
        const result = await this.callsRef.child(callId).transaction((current) => {
            const call = current ?? existingCall;
            if (
                !call ||
                call.status !== 'active' ||
                getParticipantStatus(call, userId) !== 'accepted'
            ) return;
            const now = this.clock();
            const updated = call.callerId === userId ? {
                ...call,
                callerLeftAt: now
            } : {
                ...call,
                participants: {
                    ...call.participants,
                    [userId]: { status: 'declined' }
                }
            };
            const connectedCount = (updated.callerLeftAt ? 0 : 1) +
                Object.values(updated.participants || {}).filter((participant) =>
                    (typeof participant === 'string' ? participant : participant?.status) === 'accepted'
                ).length;
            return connectedCount === 0 ? {
                ...updated,
                status: 'ended',
                endedAt: now,
                endedBy: userId,
                endReason: 'all_left'
            } : updated;
        });
        return result.committed ? result.snapshot.val() : null;
    }

    async rejoinCall(callId, userId) {
        const existingCall = await this.getCall(callId);
        const result = await this.callsRef.child(callId).transaction((current) => {
            const call = current ?? existingCall;
            if (!call || call.status !== 'active') return;
            const connectedCount = (call.callerLeftAt ? 0 : 1) +
                Object.values(call.participants || {}).filter((participant) =>
                    (typeof participant === 'string' ? participant : participant?.status) === 'accepted'
                ).length;
            if (connectedCount < 2) return;

            if (call.callerId === userId) {
                if (!call.callerLeftAt) return;
                const updated = { ...call };
                delete updated.callerLeftAt;
                return updated;
            }
            if (getParticipantStatus(call, userId) !== 'declined') return;
            return {
                ...call,
                participants: {
                    ...call.participants,
                    [userId]: { status: 'accepted' }
                }
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

    async getIndexedCalls(userId) {
        const snapshot = await this.callIdsByUserRef.child(userId).once('value');
        const index = snapshot.val() || {};
        const sortedCallIds = Object.entries(index)
            .sort((left, right) => Number(right[1] || 0) - Number(left[1] || 0))
            .map(([callId]) => callId);
        const callIds = sortedCallIds.slice(0, MAX_INDEXED_CALLS);
        const staleCallIds = sortedCallIds.slice(MAX_INDEXED_CALLS);
        await Promise.all(staleCallIds.map((callId) =>
            this.callIdsByUserRef.child(userId).child(callId).remove()
        ));
        const calls = await Promise.all(callIds.map((callId) => this.getCall(callId)));
        const missing = callIds.filter((callId, index) => !calls[index]);
        await Promise.all(missing.map((callId) =>
            this.callIdsByUserRef.child(userId).child(callId).remove()
        ));
        return calls.filter(Boolean);
    }

    async expireCall(callId, now = this.clock(), indexedCall = null) {
        const existingCall = indexedCall || await this.getCall(callId);
        const expiresAt = callExpiry(existingCall);
        if (!existingCall || existingCall.status !== 'active' || existingCall.acceptedAt ||
            !expiresAt || expiresAt > now) return existingCall;
        const result = await this.callsRef.child(callId).transaction((current) => {
            const candidate = current ?? existingCall;
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
        return result.committed ? result.snapshot.val() : await this.getCall(callId);
    }

    // Maintenance-only compatibility helper. Request handlers use the per-user index instead.
    async expirePendingCalls(now = this.clock()) {
        const snapshot = await this.callsRef.once('value');
        const calls = snapshot.val() || {};
        const expired = [];
        await Promise.all(Object.values(calls).map(async (call) => {
            const expiresAt = callExpiry(call);
            if (!call || call.status !== 'active' || call.acceptedAt ||
                !expiresAt || expiresAt > now) return;
            const updated = await this.expireCall(call.callId, now);
            if (updated?.status === 'missed' && updated.endedAt === now) expired.push(call.callId);
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
    if (call.callerId === userId) return call.callerLeftAt ? 'declined' : 'accepted';
    const participant = call.participants?.[userId];
    if (typeof participant === 'string') return participant;
    return participant?.status;
}

module.exports = { FirebaseCallStore, getParticipantStatus, CALL_TIMEOUT_MS, callExpiry };
