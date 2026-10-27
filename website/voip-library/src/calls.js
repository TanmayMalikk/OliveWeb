'use strict';


const { randomUUID } = require ('node:crypto');

class MumbleHandler {
    constructor (mumbleProxy){
        this.mumbleServer = mumbleProxy;
        this.calls = new Map(); //Prepares an empty Map (python dict)
    }

    async createCall(name, parentChannelId = 0){
        const channelId = await this.mumbleServer.addChannel(name, parentChannelId);
        const callId = randomUUID();
        const call = { callId, channelId, name};
        this.calls.set(callId, call);

        return call;
    }

    getCall (callId){
        return this.calls.get(callId);
    }

    listCalls (){
        return [...this.calls.values()];
    }

    async closeCall(callId){
        const call = this.getCall(callId);
        if (!call){return false};
        await this.mumbleServer.removeChannel(call.channelId); //mumbleserver.removeChannel not implemented yet, undefined behaviour.
        this.calls.delete(callId);
        return true;
    }

    //reasigns the user to a new channel.
    async connectUser(callId, sessionId){
        const call = this.getCall(callId);
        if (!call) {return false};
        const user = await this.mumbleServer.getState(sessionId); //mumbleServer.getState() is not implemented
        user.channel = call.channelId;// user class not implemented
        await this.mumbleServer.setState(user); // setstate() not implemented
        return true;
    }

    //returns user to their parent channel, this should be channelId 0 in most cases,
    //this channel is not an active voice channel just a waiting room of sorts.
    async disconnectUser(callId, sessionId){
        const call = this.getCall(callId);
        if (!call) {return false};
        const user = await this.mumbleServer.getState(sessionId);
        const channel = await this.mumbleServer.getChannelState(call.channelId);
        if (user.channel !== call.channelId){return false};
        user.channel = channel.parent; //returns user from a specific call channel to the parent channel
        // Parent channel would be a waiting room type of connection. Not an active call.
        await this.mumbleServer.setState(user);

        return true;
    }
}

module.exports = { MumbleHandler };