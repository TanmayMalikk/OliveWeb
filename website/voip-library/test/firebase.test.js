'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { parseServiceAccountJson } = require('../src/firebase');

test('parseServiceAccountJson returns null when inline credentials are not configured', () => {
    assert.equal(parseServiceAccountJson(undefined), null);
    assert.equal(parseServiceAccountJson('  '), null);
});

test('parseServiceAccountJson parses an inline Firebase service account', () => {
    assert.deepEqual(
        parseServiceAccountJson(JSON.stringify({
            project_id: 'olive',
            client_email: 'firebase-admin@example.test',
            private_key: 'private-key'
        })),
        {
            project_id: 'olive',
            client_email: 'firebase-admin@example.test',
            private_key: 'private-key'
        }
    );
});

test('parseServiceAccountJson rejects malformed inline credentials', () => {
    assert.throws(
        () => parseServiceAccountJson('{not-json}'),
        /FIREBASE_SERVICE_ACCOUNT_JSON must contain valid JSON/
    );
    assert.throws(
        () => parseServiceAccountJson('[]'),
        /FIREBASE_SERVICE_ACCOUNT_JSON must contain a JSON object/
    );
});
