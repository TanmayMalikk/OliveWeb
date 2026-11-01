'use strict';


const { randomUUID } = require ('node:crypto');

class MumbleHandler {
    constructor(mumbleProxy) {
        this.mumbleServer = mumbleProxy;
        this.calls = new Map();
    }

    async createCall(name, { callerId = null, participantIds = [], parentChannelId = 0 } = {}) {
        const channelId = await this.mumbleServer.addChannel(name, parentChannelId);
        const callId = randomUUID();
        const participants = [
            ...(callerId ? [{ userId: callerId, status: 'accepted' }] : []),
            ...participantIds.map((userId) => ({ userId, status: 'pending' }))
        ];
        const call = { callId, channelId, name, callerId, participants };
        this.calls.set(callId, call);

        return call;
    }

    getCall(callId) {
        return this.calls.get(callId);
    }

    listCalls() {
        return [...this.calls.values()];
    }

    listInvitations(userId) {
        return [...this.calls.values()].flatMap((call) =>
            call.participants
                .filter((participant) => participant.userId === userId && participant.status === 'pending')
                .map((participant) => ({
                    callId: call.callId,
                    callerId: call.callerId,
                    name: call.name,
                    status: participant.status
                }))
        );
    }

    async respondToInvitation(callId, userId, accepted) {
        const call = this.getCall(callId);
        const participant = call?.participants.find((entry) => entry.userId === userId);
        if (!participant || participant.status !== 'pending') return null;

        participant.status = accepted ? 'accepted' : 'declined';
        return call;
    }

    async closeCall(callId) {
        const call = this.getCall(callId);
        if (!call) return false;
        await this.mumbleServer.removeChannel(call.channelId);
        this.calls.delete(callId);
        return true;
    }

    async connectUser(callId, sessionId) {
        const call = this.getCall(callId);
        if (!call) return false;
        const user = await this.mumbleServer.getState(sessionId);
        user.channel = call.channelId;
        await this.mumbleServer.setState(user);
        return true;
    }

    async disconnectUser(callId, sessionId) {
        const call = this.getCall(callId);
        if (!call) return false;
        const user = await this.mumbleServer.getState(sessionId);
        const channel = await this.mumbleServer.getChannelState(call.channelId);
        if (user.channel !== call.channelId) return false;
        user.channel = channel.parent;
        await this.mumbleServer.setState(user);

        return true;
    }
}

module.exports = { MumbleHandler };