'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MumbleHandler } = require ('../src/calls');

test ('createCall creates and tracks a call', async () => {
    let requestedChannel;

    const fakeMumbleProxy = {
        async addChannel (name,parentChannelId){
            requestedChannel = {name, parentChannelId};
            return 42;
        }
    };

    const handler = new MumbleHandler(fakeMumbleProxy);
    const call = await handler.createCall('Support');
    assert.deepEqual(requestedChannel, {
        name: 'Support',
        parentChannelId: 0
    });
    assert.equal (call.channelId, 42);
    assert.equal (call.name, 'Support');
    assert.equal (typeof call.callId, 'string');
    assert.equal (handler.calls.get(call.callId), call);
});

test('getCall returns a call by its ID', async () => {
    const fakeMumbleProxy = {
        async addChannel() {
            return 42;
        }
    };

    const handler = new MumbleHandler(fakeMumbleProxy);
    const call = await handler.createCall('Support');

    const foundCall = handler.getCall(call.callId);

    assert.equal(foundCall, call);
    assert.equal(handler.getCall('does-not-exist'), undefined);
});

test ('ListCalls returns multiple calls', async ()=> {
    let i= 0;
    const fakeMumbleProxy = {
        async addChannel() {
            i +=1;
            return i;
        }
    };

    const handler = new MumbleHandler(fakeMumbleProxy);
    const call1 = await handler.createCall('1');
    const call2 = await handler.createCall('2');
    const listedCalls = handler.listCalls();
    assert.equal(listedCalls.length, 2);
    assert.equal(listedCalls[0], call1);
    assert.equal(listedCalls[1], call2);
});

test ('closeCall drops calls',async () =>{
    let i=0;
    const fakeMumbleProxy = {
        async addChannel(){
            i +=1;
            return i;
        },
        async removeChannel(channelId){
            return true;
        }
    };
    const handler = new MumbleHandler(fakeMumbleProxy);
    const call = await handler.createCall('1');
    assert.equal(await handler.closeCall('NaNCall'), false);
    assert.equal(await handler.closeCall(call.callId), true);
    assert.equal(handler.getCall(call.callId),undefined);
});

test('connectUser moves a user into the call channel', async () => {
    const users = new Map([
        [7, { session: 7, channel: 0, name: 'Ada' }]
    ]);
    const fakeMumbleProxy = {
        async addChannel() {
            return 42;
        },
        async getState(sessionId) {
            return { ...users.get(sessionId) };
        },
        async setState(user) {
            users.set(user.session, { ...user });
        }
    };
    const handler = new MumbleHandler(fakeMumbleProxy);
    const call = await handler.createCall('Support');

    assert.equal(await handler.connectUser(call.callId, 7), true);
    assert.equal(users.get(7).channel, 42);
    assert.equal(await handler.connectUser('missing-call', 7), false);
});

test('disconnectUser returns a user to the call channel parent', async () => {
    const users = new Map([
        [7, { session: 7, channel: 42, name: 'Ada' }]
    ]);

    const fakeMumbleProxy = {
        async addChannel() {
            return 42;
        },
        async getState(sessionId) {
            return { ...users.get(sessionId) };
        },
        async getChannelState(channelId) {
            assert.equal(channelId, 42);
            return { id: 42, parent: 0 };
        },
        async setState(user) {
            users.set(user.session, { ...user });
        }
    };

    const handler = new MumbleHandler(fakeMumbleProxy);
    const call = await handler.createCall('Support');

    const result = await handler.disconnectUser(call.callId, 7);

    assert.equal(result, true);
    assert.equal(users.get(7).channel, 0);
});