'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { configuredPort } = require('../src/config');

// Hostinger's assigned port must take priority over a local override.
test('uses the hosting platform port when it is available', () => {
    assert.equal(configuredPort({ PORT: '8123', VOIP_API_PORT: '3000' }), 8123);
});

// Local runs can choose a port without pretending to be Hostinger.
test('uses the local VoIP port when the platform did not assign one', () => {
    assert.equal(configuredPort({ VOIP_API_PORT: '4100' }), 4100);
});

// A bad hosting value should stop startup instead of listening on the wrong port.
test('rejects an invalid platform port', () => {
    assert.throws(
        () => configuredPort({ PORT: 'not-a-port' }),
        /PORT or VOIP_API_PORT must be a valid port number/,
    );
});
