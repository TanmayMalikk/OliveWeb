import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

import { doc, setDoc, getDoc, collection, getDocs } from 'firebase/firestore';

import { readFile } from 'node:fs/promises';

import { before, after, test } from 'node:test';



let env;

const profile = { username:'alice', fullName:'Test Person', affectedNames:'', dob:'2000-01-01', language:'English', situation:'', region:'', triggerHash:'a'.repeat(64), contacts:[] };

before(async () => {

  env = await initializeTestEnvironment({ projectId:'demo-olive-rules', firestore:{host:'127.0.0.1',port:8180,rules:await readFile(new URL('./firestore.rules', import.meta.url),'utf8')} });

});

after(async () => { await env?.cleanup(); });



test('owner can create/read a profile; another user and anonymous visitors cannot', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice@users.olive.invalid'}).firestore();

  const bob=env.authenticatedContext('bob-uid',{email:'bob@users.olive.invalid'}).firestore();

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),profile));

  await assertSucceeds(getDoc(doc(alice,'users/alice-uid')));

  await assertFails(getDoc(doc(bob,'users/alice-uid')));

  await assertFails(setDoc(doc(bob,'users/alice-uid'),profile));

  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'users/alice-uid')));

  await assertFails(getDocs(collection(alice,'users')));

});

test('credentials and malformed contact data cannot be stored in profiles', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice@users.olive.invalid'}).firestore();

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,password:'forbidden'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,username:'someone_else'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend'}]}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:Array(26).fill({id:'c',name:'Friend',details:'test'})}));

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid'}]}));

});


test('required profile fields and address whitelist are enforced', async () => {
  const owner=env.authenticatedContext('profile-test',{email:'alice@users.olive.invalid'}).firestore();
  const target=doc(owner,'users/profile-test');
  await assertFails(setDoc(target,{...profile,fullName:''}));
  await assertFails(setDoc(target,{...profile,dob:''}));
  const address={line1:'123 Test Street',line2:'',city:'Edmonton',province:'Alberta',postalCode:'T5J 0N3',country:'Canada'};
  await assertSucceeds(setDoc(target,{...profile,address}));
  await assertFails(setDoc(target,{...profile,address:{...address,cardNumber:'4242424242424242'}}));
  await assertFails(setDoc(target,{...profile,cardNumber:'4242424242424242'}));
});

test('new usernames enforce bounds while existing usernames remain usable', async () => {
  const owner=env.authenticatedContext('legacy',{email:'old@users.olive.invalid'}).firestore();
  const ref=doc(owner,'users/legacy');
  await assertFails(setDoc(ref,{...profile,username:'old'}));
  await env.withSecurityRulesDisabled(async context => {await setDoc(doc(context.firestore(),'users/legacy'),{...profile,username:'old'});});
  await assertSucceeds(setDoc(ref,{...profile,username:'old',situation:'Updated'}));
});
