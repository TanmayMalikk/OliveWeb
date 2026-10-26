'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { connectMumbleIce } = require ('../src/ice-config');
const { MumbleHandler } = require ('../src/calls');

test('connectMumbleIce connects and selects a virtual server', async () => {
    let requestedEndpoint;
    let selectedServerId;
    let communicatorDestroyed = false;

    const expectedServerProxy = { name: 'fake Mumble server' };

    const Ice = {
        initialize() {
            return {
                stringToProxy(endpoint) {
                    requestedEndpoint = endpoint;
                    return endpoint;
                },
                async destroy() {
                    communicatorDestroyed = true;
                }
            };
        }
    };

    const MumbleServer = {
        MetaPrx: {
            async checkedCast(proxy) {
                assert.equal(proxy, requestedEndpoint);

                return {
                    async getServer(serverId) {
                        selectedServerId = serverId;
                        return expectedServerProxy;
                    }
                };
            }
        }
    };

    const connection = await connectMumbleIce({
        Ice,
        MumbleServer,
        host: 'localhost',
        port: 6502,
        serverId: 1
    });

    assert.equal(requestedEndpoint, 'Meta:tcp -h localhost -p 6502');
    assert.equal(selectedServerId, 1);
    assert.equal(connection.mumbleServer, expectedServerProxy);

    await connection.close();
    assert.equal(communicatorDestroyed, true);
});

test ('export functionality',async () => {
    assert.equal(typeof MumbleHandler, 'function');
    assert.equal(typeof connectMumbleIce, 'function');
});