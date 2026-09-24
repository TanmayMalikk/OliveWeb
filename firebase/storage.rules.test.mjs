import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, uploadString, getBytes, deleteObject } from 'firebase/storage';
import { readFile } from 'node:fs/promises';
import { before, after, test } from 'node:test';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-olive-rules',
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: await readFile(new URL('./storage.rules', import.meta.url), 'utf8'
      ),
    },
  });
});

after(async () => { await env?.cleanup(); });

test('only the account owner can read and write its profile object', async () => {
  const alice = env.authenticatedContext('alice-uid').storage();
  const bob = env.authenticatedContext('bob-uid').storage();
  const anonymous = env.unauthenticatedContext().storage();
  const path = 'accounts/alice-uid/profile.json';
  const body = JSON.stringify({ username: 'alice', fullName: 'Test Person' });
  await assertSucceeds(uploadString(ref(alice, path), body, 'raw', { contentType: 'application/json' }));
  await assertSucceeds(getBytes(ref(alice, path)));
  await assertFails(getBytes(ref(bob, path)));
  await assertFails(uploadString(ref(bob, path), body, 'raw', { contentType: 'application/json' }));
  await assertFails(getBytes(ref(anonymous, path)));
  await assertSucceeds(deleteObject(ref(alice, path)));
});

test('profile objects enforce their path, type and size', async () => {
  const owner = env.authenticatedContext('alice-uid').storage();
  await assertFails(uploadString(ref(owner, 'accounts/alice-uid/extra.json'), '{}', 'raw', { contentType: 'application/json' }));
  await assertFails(uploadString(ref(owner, 'accounts/alice-uid/profile.json'), '{}', 'raw', { contentType: 'text/plain' }));
  await assertFails(uploadString(ref(owner, 'accounts/alice-uid/profile.json'), 'x'.repeat(64 * 1024 + 1), 'raw', { contentType: 'application/json' }));
});

test('activity history belongs to its account and accepts only small JSON events', async () => {
  const owner = env.authenticatedContext('alice-uid').storage();
  const other = env.authenticatedContext('bob-uid').storage();
  const path = 'accounts/alice-uid/history/settings/12345678-1234.json';
  await assertSucceeds(uploadString(ref(owner, path), '{"action":"saved"}', 'raw', { contentType: 'application/json' }));
  await assertSucceeds(getBytes(ref(owner, path)));
  await assertFails(getBytes(ref(other, path)));
  await assertFails(uploadString(ref(other, path), '{}', 'raw', { contentType: 'application/json' }));
  await assertFails(uploadString(ref(owner, 'accounts/alice-uid/history/other/12345678-1234.json'), '{}', 'raw', { contentType: 'application/json' }));
  await assertFails(uploadString(ref(owner, path), '{}', 'raw', { contentType: 'text/plain' }));
  await assertFails(uploadString(ref(owner, path), 'x'.repeat(64 * 1024 + 1), 'raw', { contentType: 'application/json' }));
});
