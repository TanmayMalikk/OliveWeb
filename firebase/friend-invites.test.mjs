// Exercises the production invite transaction against the local Firestore emulator.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {compileFunction} from 'node:vm';
import {randomUUID} from 'node:crypto';

const require = createRequire(new URL('../functions/package.json', import.meta.url));
const {initializeApp, deleteApp} = require('firebase-admin/app');
const {getFirestore, FieldValue} = require('firebase-admin/firestore');
const {HttpsError} = require('firebase-functions/v2/https');

test('two simultaneous claims have one winner and refresh races never leave two valid codes', async () => {
  const app = initializeApp({projectId: 'demo-olive-rules'}, `invite-${randomUUID()}`);
  const db = getFirestore(app);
  const prefix = `invite-${randomUUID()}`;
  const owner = `${prefix}-owner`, first = `${prefix}-first`, second = `${prefix}-second`;
  const users = new Map([owner, first, second].map(uid => [uid, {uid, customClaims: {role: 'USER'}}]));
  const imports = {
    'firebase-functions/v2/https': {onCall: (_, fn) => fn, HttpsError},
    './firebaseAdminClient': {db, FieldValue, auth: {getUser: async uid => users.get(uid)}},
    './safetyPlanAccess': {safetyCaller: async request => {
      if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in first.');
      return {uid: request.auth.uid, role: 'USER'};
    }},
    './accountPolicy': {roleFromUser: user => user.customClaims.role},
  };
  const api = {};
  compileFunction(await readFile(new URL('../functions/src/friendInvites.js', import.meta.url), 'utf8'), ['require', 'exports'])(
    name => imports[name] ?? require(name), api,
  );
  try {
    for (const [index, uid] of [owner, first, second].entries()) {
      await db.collection('accountDirectory').doc(`${prefix}_${index}`).set({uid, username: `invite_person_${index}`, role: 'USER'});
    }
    const invite = await api.friendInviteCreate({auth: {uid: owner}});
    const results = await Promise.allSettled([first, second].map(uid => api.friendInviteClaim({auth: {uid}, data: {code: invite.code}})));
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    const rejected = results.find(result => result.status === 'rejected');
    assert.equal(rejected.reason.code, 'failed-precondition');
    const accepted = (await db.collection('friendInviteOwners').doc(owner).get()).get('claimedBy');
    const retry = await api.friendInviteClaim({auth: {uid: accepted}, data: {code: invite.code}});
    assert.ok(retry.friendshipId.includes(accepted));
    const refreshed = await Promise.all([true, true].map(refresh => api.friendInviteCreate({auth: {uid: owner}, data: {refresh}})));
    const active = (await db.collection('friendInviteOwners').doc(owner).get()).get('code');
    assert.ok(refreshed.some(item => item.code === active));
    for (const previous of refreshed.filter(item => item.code !== active)) {
      await assert.rejects(api.friendInviteClaim({auth: {uid: second}, data: {code: previous.code}}), {code: 'failed-precondition'});
    }
    await assert.rejects(api.friendInviteClaim({data: {code: active}}), {code: 'unauthenticated'});
  } finally {
    await db.terminate();
    await deleteApp(app);
  }
});
