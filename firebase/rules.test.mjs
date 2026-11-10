import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

import {
  doc, setDoc, getDoc, collection, getDocs, query, where,
  serverTimestamp, writeBatch, updateDoc, deleteDoc
} from 'firebase/firestore';
import { ref, set, get, remove } from 'firebase/database';

import { readFile } from 'node:fs/promises';

import { before, after, test } from 'node:test';



let env;

const profile = { username:'alice_user', fullName:'Test Person', affectedNames:'', dob:'2000-01-01', language:'English', situation:'', region:'', triggerHash:'a'.repeat(64), contacts:[] };

before(async () => {

  env = await initializeTestEnvironment({ projectId:'demo-olive-rules',
    firestore:{host:'127.0.0.1',port:8180,rules:await readFile(new URL('./firestore.rules', import.meta.url),'utf8')},
    database:{host:'127.0.0.1',port:9100,rules:await readFile(new URL('./database.rules.json', import.meta.url),'utf8')} });

});

after(async () => { await env?.cleanup(); });

test('VoIP call records and push-token registrations are server-only', async () => {
  const alice = env.authenticatedContext('alice-uid').database();
  await env.withSecurityRulesDisabled(async context => {
    const admin = context.database();
    await set(ref(admin, 'calls/call-1'), { callerId: 'alice-uid' });
    await set(ref(admin, 'users/alice-uid/fcmTokens/phone'), 'fcm-token');
  });
  await assertFails(get(ref(alice, 'calls/call-1')));
  await assertFails(set(ref(alice, 'calls/call-2'), { callerId: 'alice-uid' }));
  await assertFails(get(ref(alice, 'users/alice-uid/fcmTokens/phone')));
  await assertFails(set(ref(alice, 'users/alice-uid/fcmTokens/phone'), 'forged-token'));
});

test('sync invalidation markers are owner-readable and server-write-only', async () => {
  const alice = env.authenticatedContext('sync-alice').firestore();
  const bob = env.authenticatedContext('sync-bob').firestore();
  const anonymous = env.unauthenticatedContext().firestore();
  const marker = {
    ownerUid:'sync-alice', allowedUsers:['sync-alice'], revision:5, urgentRevision:2,
    safetyRevision:1, mediaRevision:2, conversationRevision:3, accountRevision:4,
    updatedAt:serverTimestamp(),
  };
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(),'syncInvalidations/sync-alice'),marker);
  });

  await assertSucceeds(getDoc(doc(alice,'syncInvalidations/sync-alice')));
  await assertFails(getDoc(doc(bob,'syncInvalidations/sync-alice')));
  await assertFails(getDoc(doc(anonymous,'syncInvalidations/sync-alice')));
  await assertFails(getDocs(collection(alice,'syncInvalidations')));
  await assertFails(setDoc(doc(alice,'syncInvalidations/sync-alice'),marker));
  await assertFails(updateDoc(doc(alice,'syncInvalidations/sync-alice'),{revision:6}));
  await assertFails(deleteDoc(doc(alice,'syncInvalidations/sync-alice')));
});

test('malformed server sync markers are unreadable even by their path owner', async () => {
  const alice = env.authenticatedContext('sync-shape-alice').firestore();
  const base = {
    ownerUid:'sync-shape-alice', allowedUsers:['sync-shape-alice'], revision:1, urgentRevision:1,
    safetyRevision:1, mediaRevision:1, conversationRevision:1, accountRevision:1,
    updatedAt:serverTimestamp(),
  };
  const malformed = {
    extra:{...base, unexpected:'schema pollution'},
    wrongOwner:{...base, ownerUid:'sync-other'},
    mixedReaders:{...base, allowedUsers:['sync-shape-alice','sync-other']},
    missingRevision:{ownerUid:base.ownerUid, allowedUsers:base.allowedUsers, urgentRevision:1,
      safetyRevision:1, mediaRevision:1, conversationRevision:1, accountRevision:1,
      updatedAt:serverTimestamp()},
    missingUrgentRevision:{ownerUid:base.ownerUid, allowedUsers:base.allowedUsers, revision:1,
      safetyRevision:1, mediaRevision:1, conversationRevision:1, accountRevision:1,
      updatedAt:serverTimestamp()},
    negativeUrgentRevision:{...base, urgentRevision:-1},
    wrongUrgentRevisionType:{...base, urgentRevision:'1'},
    fractionalUrgentRevision:{...base, urgentRevision:1.5},
    negative:{...base, safetyRevision:-1},
    wrongType:{...base, mediaRevision:'1'},
    fractional:{...base, conversationRevision:1.5},
    wrongTimestamp:{...base, updatedAt:Date.now()},
  };
  await env.withSecurityRulesDisabled(async context => {
    for (const [id, value] of Object.entries(malformed)) {
      await setDoc(doc(context.firestore(),`syncInvalidations/${id}`),value);
    }
  });

  // Seed malformed rows at the authenticated owner's path one at a time to prove the schema gate,
  // not only the path ownership check, denies every invalid shape.
  for (const value of Object.values(malformed)) {
    await env.withSecurityRulesDisabled(async context => {
      await setDoc(doc(context.firestore(),'syncInvalidations/sync-shape-alice'),value);
    });
    await assertFails(getDoc(doc(alice,'syncInvalidations/sync-shape-alice')));
  }
});



test('owner can create/read a profile; another user and anonymous visitors cannot', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice_user@users.olive.invalid'}).firestore();

  const bob=env.authenticatedContext('bob-uid',{email:'bob@users.olive.invalid'}).firestore();

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),profile));

  await assertSucceeds(getDoc(doc(alice,'users/alice-uid')));

  await assertFails(getDoc(doc(bob,'users/alice-uid')));

  await assertFails(setDoc(doc(bob,'users/alice-uid'),profile));

  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'users/alice-uid')));

  await assertFails(getDocs(collection(alice,'users')));

});

test('credentials and malformed contact data cannot be stored in profiles', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice_user@users.olive.invalid'}).firestore();

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,password:'forbidden'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,username:'someone_else'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend'}]}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:Array(26).fill({id:'c',name:'Friend',details:'test'})}));

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid'}]}));
  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',safeToStay:true}]}));
  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',linkedUid:'friend-uid'}]}));
  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',linkedUid:5}]}));
  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',linkedUid:'x'.repeat(129)}]}));
  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',safeToStay:'yes'}]}));

});


test('required profile fields and address whitelist are enforced', async () => {
  const owner=env.authenticatedContext('profile-test',{email:'alice_user@users.olive.invalid'}).firestore();
  const target=doc(owner,'users/profile-test');
  await assertFails(setDoc(target,{...profile,fullName:''}));
  await assertFails(setDoc(target,{...profile,dob:''}));
  const address={line1:'123 Test Street',line2:'',city:'Edmonton',province:'Alberta',postalCode:'T5J 0N3',country:'Canada'};
  await assertSucceeds(setDoc(target,{...profile,address}));
  await assertFails(setDoc(target,{...profile,address:{...address,cardNumber:'4242424242424242'}}));
  await assertFails(setDoc(target,{...profile,cardNumber:'4242424242424242'}));
  await assertSucceeds(setDoc(target,{...profile,role:'SAFE_CONTACT',triggerHash:''}));
  await assertFails(setDoc(target,{...profile,role:'OWNER'}));
});

test('new usernames enforce bounds while existing usernames remain usable', async () => {
  const owner=env.authenticatedContext('legacy',{email:'old@users.olive.invalid'}).firestore();
  const ref=doc(owner,'users/legacy');
  await assertFails(setDoc(ref,{...profile,username:'old'}));
  await env.withSecurityRulesDisabled(async context => {await setDoc(doc(context.firestore(),'users/legacy'),{...profile,username:'old'});});
  await assertSucceeds(setDoc(ref,{...profile,username:'old',situation:'Updated'}));
});

test('file metadata is owned, shareable for reads, and cannot be reshaped', async () => {
  const alice=env.authenticatedContext('file-alice').firestore();
  const bob=env.authenticatedContext('file-bob').firestore();
  const charlie=env.authenticatedContext('file-charlie').firestore();
  const target=doc(alice,'files/file-metadata-1');
  const metadata={owner_id:'file-alice',storage_path:'users/file-alice/media/file-metadata-1',
    file_name:'Evidence file',file_type:'image/jpeg',allowed_users:['file-alice','file-bob'],
    created_at:serverTimestamp()};
  await assertSucceeds(setDoc(target,metadata));
  await assertSucceeds(getDoc(doc(bob,'files/file-metadata-1')));
  await assertFails(getDoc(doc(charlie,'files/file-metadata-1')));
  await assertFails(setDoc(doc(bob,'files/file-2'),{...metadata,storage_path:'users/file-alice/media/file-2'}));
  await assertFails(setDoc(doc(alice,'files/wrong-path'),metadata));
  await assertFails(updateDoc(doc(bob,'files/file-metadata-1'),{allowed_users:['file-alice','file-bob','file-charlie']}));
  await assertFails(updateDoc(target,{unexpected:'not allowed'}));
  await assertFails(updateDoc(target,{ocr_text:'Recognized document text',ocr_confidence:0.82,ocr_successful:true}));
  await assertFails(updateDoc(target,{ocr_confidence:1.2}));
  await assertFails(updateDoc(target,{ocr_text:'x'.repeat(50001)}));
  await assertFails(setDoc(doc(alice,'files/file-metadata-plaintext-ocr'),{...metadata,
    storage_path:'users/file-alice/media/file-metadata-plaintext-ocr',
    ocr_text:'Server-readable OCR must never be accepted',ocr_confidence:0.82,ocr_successful:true}));
  await assertFails(setDoc(doc(alice,'files/client-injected-encrypted-metadata'),{
    owner_id:'file-alice',object_key:'users/file-alice/media/client-injected-encrypted-metadata',
    encrypted_metadata:'untrusted-client-ciphertext',wrapped_keys:{'file-alice':'wrapped-key'},
    allowed_users:['file-alice'],ciphertext_size:1024,ciphertext_sha256:'a'.repeat(64),
    encryption_version:1,created_at:serverTimestamp()}));
  await assertFails(setDoc(doc(alice,'files/file-metadata-2'),{...metadata,
    storage_path:'users/file-alice/media/file-metadata-2',ocr_text:'Incomplete OCR metadata'}));
  await assertSucceeds(updateDoc(target,{allowed_users:['file-alice','file-charlie']}));
  await assertSucceeds(getDoc(doc(charlie,'files/file-metadata-1')));
  await assertFails(deleteDoc(doc(bob,'files/file-metadata-1')));
  await assertSucceeds(deleteDoc(target));
});

test('authorized users can query encrypted media without exposing other file records', async () => {
  const alice=env.authenticatedContext('media-alice').firestore();
  const bob=env.authenticatedContext('media-bob').firestore();
  const charlie=env.authenticatedContext('media-charlie').firestore();
  const anonymous=env.unauthenticatedContext().firestore();
  const mediaId='encrypted-media-1';
  const encrypted={owner_id:'media-alice',object_key:`users/media-alice/media/${mediaId}`,
    encrypted_metadata:'encrypted-display-metadata',wrapped_keys:{'media-alice':'wrapped-owner-key','media-bob':'wrapped-recipient-key'},
    allowed_users:['media-alice','media-bob'],ciphertext_size:4096,
    ciphertext_sha256:'a'.repeat(64),encryption_version:1,created_at:serverTimestamp()};

  // Cloud Functions writes encrypted records with the Admin SDK, which bypasses client rules.
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(),`files/${mediaId}`),encrypted);
    await setDoc(doc(context.firestore(),'files/malformed-encrypted-media'),{
      ...encrypted,object_key:'users/media-alice/media/malformed-encrypted-media',unexpected:'forbidden'});
  });

  const visibleToAlice=query(collection(alice,'files'),where('allowed_users','array-contains','media-alice'));
  const visibleToBob=query(collection(bob,'files'),where('allowed_users','array-contains','media-bob'));
  await assertSucceeds(getDocs(visibleToAlice));
  await assertSucceeds(getDocs(visibleToBob));
  // A caller cannot satisfy the rule by querying another user's membership value.
  await assertFails(getDocs(query(collection(charlie,'files'),where('allowed_users','array-contains','media-alice'))));
  await assertFails(getDocs(collection(alice,'files')));
  await assertFails(getDocs(query(collection(anonymous,'files'),where('allowed_users','array-contains','media-alice'))));
  // Direct reads retain full schema validation even for a UID present in allowed_users.
  await assertFails(getDoc(doc(alice,'files/malformed-encrypted-media')));
});

test('users and safe contacts get encrypted private DMs and only the sender can unsend', async () => {
  const alice=env.authenticatedContext('social-a',{email:'social_alice@users.olive.invalid'}).firestore();
  const bob=env.authenticatedContext('social-b',{email:'social_bob@safe.olive.invalid'}).firestore();
  const charlie=env.authenticatedContext('social-c',{email:'social_charlie@users.olive.invalid'}).firestore();
  await assertSucceeds(setDoc(doc(alice,'messageDirectory/social_alice'),{uid:'social-a',username:'social_alice',publicKey:'alice-key',updatedAt:serverTimestamp()}));
  await assertSucceeds(setDoc(doc(bob,'messageDirectory/social_bob'),{uid:'social-b',username:'social_bob',publicKey:'bob-key',updatedAt:serverTimestamp()}));
  await assertSucceeds(setDoc(doc(charlie,'messageDirectory/social_charlie'),{uid:'social-c',username:'social_charlie',publicKey:'charlie-key',updatedAt:serverTimestamp()}));
  const duplicateAlice=env.authenticatedContext('social-duplicate',{email:'social_alice@safe.olive.invalid'}).firestore();
  await assertFails(setDoc(doc(duplicateAlice,'messageDirectory/social_alice'),
    {uid:'social-duplicate',username:'social_alice',publicKey:'duplicate-key',updatedAt:serverTimestamp()}));

  await assertSucceeds(getDoc(doc(alice,'messageDirectory/social_bob')));
  await assertFails(getDocs(query(collection(alice,'messageDirectory'),where('username','>=','social_b'))));
  const aliceDevice={uid:'social-a',deviceId:'alice-web-device-1',publicKey:'alice-web-key',
    platform:'WEB',mgf1Digest:'SHA-256',updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(doc(alice,'messageDirectory/social_alice/devices/alice-web-device-1'),aliceDevice));
  await assertSucceeds(getDoc(doc(bob,'messageDirectory/social_alice/devices/alice-web-device-1')));
  await assertFails(setDoc(doc(bob,'messageDirectory/social_alice/devices/stolen-web-device'),
    {...aliceDevice,deviceId:'stolen-web-device'}));
  const requestId='social-a--social-b';
  const friendshipId='social-a--social-b';
  const request={senderUid:'social-a',senderUsername:'social_alice',recipientUid:'social-b',
    recipientUsername:'social_bob',friendshipId,createdAt:serverTimestamp()};
  await assertSucceeds(setDoc(doc(alice,'friendRequests/'+requestId),request));
  await assertSucceeds(getDocs(query(collection(bob,'friendRequests'),where('recipientUid','==','social-b'))));
  await assertSucceeds(getDocs(query(collection(alice,'friendRequests'),where('senderUid','==','social-a'))));
  await assertFails(deleteDoc(doc(charlie,'friendRequests/'+requestId)));
  await assertSucceeds(deleteDoc(doc(alice,'friendRequests/'+requestId)));
  await assertSucceeds(setDoc(doc(alice,'friendRequests/'+requestId),request));
  await assertFails(setDoc(doc(alice,'friendRequests/'+requestId),request));

  await assertFails(setDoc(doc(alice,'friendRequests/social-a--social-a'),{...request,recipientUid:'social-a',
    recipientUsername:'social_alice',friendshipId:'social-a--social-a'}));
  await assertFails(setDoc(doc(bob,'friendRequests/social-b--social-a'),{senderUid:'social-b',
    senderUsername:'social_bob',recipientUid:'social-a',recipientUsername:'social_alice',
    friendshipId,createdAt:serverTimestamp()}));
  await assertFails(setDoc(doc(charlie,'friendships/'+friendshipId),{participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    acceptedRequestId:requestId,createdAt:serverTimestamp()}));

  const accept=writeBatch(bob);
  accept.set(doc(bob,'friendships/'+friendshipId),{participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    acceptedRequestId:requestId,createdAt:serverTimestamp()});
  accept.set(doc(bob,'friendLists/social-a/friends/'+friendshipId),{friendshipId,friendUid:'social-b',
    friendUsername:'social_bob',createdAt:serverTimestamp()});
  accept.set(doc(bob,'friendLists/social-b/friends/'+friendshipId),{friendshipId,friendUid:'social-a',
    friendUsername:'social_alice',createdAt:serverTimestamp()});
  const conversation={friendshipId,participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    createdAt:serverTimestamp(),updatedAt:serverTimestamp(),lastMessage:'',lastMessageId:'',lastSenderUid:''};
  accept.set(doc(bob,'conversations/'+friendshipId),conversation);
  accept.delete(doc(bob,'friendRequests/'+requestId));
  await assertSucceeds(accept.commit());
  await assertSucceeds(getDocs(collection(alice,'friendLists/social-a/friends')));
  await assertSucceeds(getDocs(collection(bob,'friendLists/social-b/friends')));
  await assertFails(getDocs(collection(charlie,'friendLists/social-a/friends')));
  await assertFails(setDoc(doc(alice,'friendRequests/'+requestId),request));

  const sharedLocation=doc(alice,'sharedLocations/social-a/recipients/social-b');
  await assertSucceeds(setDoc(sharedLocation,{ownerUid:'social-a',recipientUid:'social-b',friendshipId,
    ciphertext:'encrypted-static-location',wrappedKey:'wrapped-static-key',updatedAt:serverTimestamp()}));
  await assertSucceeds(getDoc(doc(bob,'sharedLocations/social-a/recipients/social-b')));
  await assertFails(getDoc(doc(charlie,'sharedLocations/social-a/recipients/social-b')));
  await assertFails(setDoc(doc(bob,'sharedLocations/social-a/recipients/social-b'),{
    ownerUid:'social-a',recipientUid:'social-b',friendshipId,ciphertext:'changed',wrappedKey:'changed',updatedAt:serverTimestamp()}));
  await assertFails(setDoc(doc(alice,'sharedLocations/social-a/recipients/social-b'),{
    ownerUid:'social-a',recipientUid:'social-b',friendshipId,latitude:53.5461,longitude:-113.4937,updatedAt:serverTimestamp()}));

  const liveShare=doc(alice,'liveLocationShares/social-a/recipients/social-b');
  await assertSucceeds(setDoc(liveShare,{ownerUid:'social-a',recipientUid:'social-b',friendshipId,
    wrappedKey:'wrapped-live-key',createdAt:serverTimestamp()}));
  await assertSucceeds(getDoc(doc(bob,'liveLocationShares/social-a/recipients/social-b')));
  await assertFails(getDoc(doc(charlie,'liveLocationShares/social-a/recipients/social-b')));
  await assertFails(setDoc(doc(bob,'liveLocationShares/social-a/recipients/social-b'),{
    ownerUid:'social-a',recipientUid:'social-b',friendshipId,wrappedKey:'changed',createdAt:serverTimestamp()}));

  await assertSucceeds(getDoc(doc(alice,'conversations/'+friendshipId)));
  await assertSucceeds(getDocs(query(collection(alice,'conversations'),where('participantIds','array-contains','social-a'))));
  await assertFails(getDocs(collection(alice,'conversations')));
  await assertFails(getDocs(query(collection(alice,'conversations'),where('participantIds','array-contains','social-b'))));
  await assertFails(getDoc(doc(charlie,'conversations/'+friendshipId)));
  const deviceRoomKey={uid:'social-a',deviceId:'alice-web-device-1',publicKey:'alice-web-key',
    wrappedKey:'wrapped-for-alice-browser',updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(doc(bob,`conversations/${friendshipId}/deviceKeys/social-a--alice-web-device-1`),deviceRoomKey));
  await assertSucceeds(getDoc(doc(alice,`conversations/${friendshipId}/deviceKeys/social-a--alice-web-device-1`)));
  await assertFails(getDoc(doc(charlie,`conversations/${friendshipId}/deviceKeys/social-a--alice-web-device-1`)));
  await assertFails(setDoc(doc(charlie,`conversations/${friendshipId}/deviceKeys/social-c--alice-web-device-1`),
    {...deviceRoomKey,uid:'social-c'}));

  const plaintextRef=doc(alice,'conversations/'+friendshipId+'/messages/plaintext-message');
  await assertFails(setDoc(plaintextRef,{senderUid:'social-a',senderUsername:'social_alice',body:'Private hello',
    deleted:false,sentAt:serverTimestamp(),clientSentAt:Date.now()}));

  const roomKeys={'social-a':'wrapped-a','social-b':'wrapped-b'};
  const participantPublicKeys={'social-a':'alice-key','social-b':'bob-key'};
  await assertSucceeds(updateDoc(doc(alice,'conversations/'+friendshipId),
    {roomKeys,participantPublicKeys,updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(bob,'conversations/'+friendshipId),
    {roomKeys:{'social-a':'replacement','social-b':'replacement'},updatedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(doc(alice,'messageDirectory/social_alice'),
    {publicKey:'alice-key-rotated',updatedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(doc(alice,'conversations/'+friendshipId),{
    roomKeys:{'social-a':'wrapped-a-rotated','social-b':'wrapped-b-rotated'},
    participantPublicKeys:{'social-a':'alice-key-rotated','social-b':'bob-key'},updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(bob,'conversations/'+friendshipId),{
    roomKeys:{'social-a':'unauthorized-replacement','social-b':'unauthorized-replacement'},
    participantPublicKeys:{'social-a':'alice-key-rotated','social-b':'bob-key'},updatedAt:serverTimestamp()}));
  const encryptedRef=doc(alice,'conversations/'+friendshipId+'/messages/message-2');
  await assertSucceeds(setDoc(encryptedRef,{senderUid:'social-a',senderUsername:'social_alice',
    ciphertext:'encrypted-payload',deleted:false,sentAt:serverTimestamp(),clientSentAt:Date.now()}));
  await assertSucceeds(getDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-2')));
  await assertFails(setDoc(doc(bob,'conversations/'+friendshipId+'/messages/safe-contact-plaintext'),
    {senderUid:'social-b',senderUsername:'social_bob',body:'Unencrypted reply',deleted:false,
      sentAt:serverTimestamp(),clientSentAt:Date.now()}));
  await assertSucceeds(setDoc(doc(bob,'conversations/'+friendshipId+'/messages/safe-contact-encrypted'),
    {senderUid:'social-b',senderUsername:'social_bob',ciphertext:'encrypted-safe-contact-reply',deleted:false,
      sentAt:serverTimestamp(),clientSentAt:Date.now()}));
  await assertFails(updateDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-2'),
    {ciphertext:'Message unsent',deleted:true,deletedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(encryptedRef,{ciphertext:'Message unsent',deleted:true,deletedAt:serverTimestamp()}));
  const visible=await getDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-2'));
  if(visible.data()?.ciphertext!=='Message unsent' || visible.data()?.deleted!==true)
    throw new Error('Encrypted unsent tombstone was not visible to both friends.');
  await assertFails(setDoc(doc(charlie,'conversations/'+friendshipId+'/messages/message-3'),
    {senderUid:'social-c',senderUsername:'social_charlie',ciphertext:'stolen',deleted:false,
      sentAt:serverTimestamp(),clientSentAt:Date.now()}));
});

test('support conversations allow one user, safe contact, staff member, and admin', async () => {
  const owner=env.authenticatedContext('support-owner',{email:'support_owner@users.olive.invalid'}).firestore();
  const staff=env.authenticatedContext('support-staff',{email:'staff@example.ca',role:'STAFF'}).firestore();
  const contact=env.authenticatedContext('support-contact',{email:'support_contact@safe.olive.invalid'}).firestore();
  const admin=env.authenticatedContext('support-admin',{email:'admin@example.ca',role:'ADMIN'}).firestore();
  const stranger=env.authenticatedContext('support-stranger',{email:'support_stranger@users.olive.invalid'}).firestore();
  const directory=[
    [owner,'support_owner','support-owner','owner-key'],
    [staff,'support_staff','support-staff','staff-key'],
    [contact,'support_contact','support-contact','contact-key'],
    [admin,'support_admin','support-admin','admin-key'],
  ];
  for (const [client,username,uid,publicKey] of directory) {
    await assertSucceeds(setDoc(doc(client,`messageDirectory/${username}`),
      {uid,username,publicKey,updatedAt:serverTimestamp()}));
  }
  await assertFails(setDoc(doc(staff,'friendRequests/support-staff--support-owner'),{
    senderUid:'support-staff',senderUsername:'support_staff',recipientUid:'support-owner',
    recipientUsername:'support_owner',friendshipId:'support-staff--support-owner',createdAt:serverTimestamp(),
  }));
  const conversationId='support-support-owner-ticket-1';
  const conversation={type:'SUPPORT',ownerUid:'support-owner',staffUid:'support-staff',adminUid:'support-admin',
    supportOrigin:'SUPPORT',supportTag:'NEEDS_ADMIN',
    friendshipId:conversationId,participantIds:['support-owner','support-staff','support-contact','support-admin'],
    participantUsernames:{'support-owner':'support_owner','support-staff':'support_staff','support-contact':'support_contact','support-admin':'support_admin'},
    createdAt:serverTimestamp(),updatedAt:serverTimestamp(),lastMessage:'',lastMessageId:'',lastSenderUid:''};
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(),`conversations/${conversationId}`),conversation);
  });
  await assertSucceeds(getDoc(doc(owner,`conversations/${conversationId}`)));
  await assertSucceeds(getDoc(doc(staff,`conversations/${conversationId}`)));
  await assertSucceeds(getDoc(doc(contact,`conversations/${conversationId}`)));
  await assertSucceeds(getDoc(doc(admin,`conversations/${conversationId}`)));
  await assertFails(getDoc(doc(stranger,`conversations/${conversationId}`)));
  await assertFails(updateDoc(doc(owner,`conversations/${conversationId}`),
    {participantIds:['support-owner','support-staff'],updatedAt:serverTimestamp()}));
  const keys={roomKeys:{'support-owner':'owner-wrap','support-staff':'staff-wrap','support-contact':'contact-wrap','support-admin':'admin-wrap'},
    participantPublicKeys:{'support-owner':'owner-key','support-staff':'staff-key','support-contact':'contact-key','support-admin':'admin-key'},
    updatedAt:serverTimestamp()};
  await assertSucceeds(updateDoc(doc(owner,`conversations/${conversationId}`),keys));
  await assertFails(updateDoc(doc(staff,`conversations/${conversationId}`),{
    roomKeys:{'support-owner':'owner-wrap','support-staff':'staff-wrap'},
    participantPublicKeys:{'support-owner':'owner-key','support-staff':'staff-key'},updatedAt:serverTimestamp()}));
  await assertSucceeds(setDoc(doc(staff,`conversations/${conversationId}/messages/staff-message`),
    {senderUid:'support-staff',senderUsername:'support_staff',ciphertext:'encrypted-support-message',
      deleted:false,sentAt:serverTimestamp(),clientSentAt:Date.now()}));
  for (const [client,uid,username] of [[staff,'support-staff','support_staff'],
    [owner,'support-owner','support_owner'],[admin,'support-admin','support_admin']]) {
    const batch=writeBatch(client);
    const messageId=`batch-${uid}`;
    batch.set(doc(client,`conversations/${conversationId}/messages/${messageId}`),
      {senderUid:uid,senderUsername:username,ciphertext:'encrypted-support-message',
        deleted:false,sentAt:serverTimestamp(),clientSentAt:Date.now()});
    batch.update(doc(client,`conversations/${conversationId}`),
      {lastMessage:'Secure message',lastMessageId:messageId,lastSenderUid:uid,
        updatedAt:serverTimestamp()});
    await assertSucceeds(batch.commit());
  }
  await assertSucceeds(getDoc(doc(contact,`conversations/${conversationId}/messages/staff-message`)));
});

test('encrypted live coordinates are overwritten by their owner and readable only by authorized contacts', async () => {
  const owner=env.authenticatedContext('live-owner').database();
  const contact=env.authenticatedContext('live-contact').database();
  const stranger=env.authenticatedContext('live-stranger').database();
  const path='liveLocations/live-owner';
  const location={ciphertext:'encrypted-current-location',updatedAt:Date.now(),active:true,
    authorized:{'live-contact':true}};
  const trailPath='liveLocationTrails/live-owner';
  const trail={'1770000000000':{ciphertext:'encrypted-trail-one',updatedAt:1770000000000},
    '1770000002000':{ciphertext:'encrypted-trail-two',updatedAt:1770000002000}};
  await assertSucceeds(set(ref(owner,path),location));
  await assertSucceeds(set(ref(owner,trailPath),trail));
  await assertSucceeds(get(ref(contact,path)));
  await assertSucceeds(get(ref(contact,trailPath)));
  await assertFails(get(ref(stranger,path)));
  await assertFails(get(ref(stranger,trailPath)));
  await assertFails(set(ref(contact,path),{...location,ciphertext:'changed'}));
  await assertFails(set(ref(owner,trailPath+'/bad'),{latitude:53.55,longitude:-113.49,updatedAt:Date.now()}));
  await assertFails(set(ref(owner,path),{...location,latitude:53.55,longitude:-113.49}));
  await assertSucceeds(set(ref(owner,path),{...location,ciphertext:'encrypted-new-location',updatedAt:Date.now()}));
  await assertSucceeds(set(ref(owner,path),{...location,active:false}));
  const retained=await assertSucceeds(get(ref(contact,path)));
  if(retained.val()?.active!==false || retained.val()?.ciphertext!=='encrypted-current-location')
    throw new Error('The authorized contact could not read the retained last-known location.');
  await assertSucceeds(remove(ref(owner,path)));
  await assertSucceeds(remove(ref(owner,trailPath)));
});

test('organization locations are written by users and readable only by admins', async () => {
  const user=env.authenticatedContext('map-user',{role:'USER'}).database();
  const admin=env.authenticatedContext('map-admin',{role:'ADMIN'}).database();
  const staff=env.authenticatedContext('map-staff',{role:'STAFF'}).database();
  const safeContact=env.authenticatedContext('map-safe',{role:'SAFE_CONTACT'}).database();
  const path='adminLiveLocations/map-user';
  const point={name:'Map User',latitude:53.5461,longitude:-113.4938,accuracy:8,
    updatedAt:Date.now(),active:true};
  await assertSucceeds(set(ref(user,path),point));
  await assertSucceeds(get(ref(admin,'adminLiveLocations')));
  await assertFails(get(ref(staff,'adminLiveLocations')));
  await assertFails(get(ref(safeContact,'adminLiveLocations')));
  await assertFails(set(ref(admin,path),point));
  await assertFails(set(ref(user,path),{...point,latitude:91}));
  await assertFails(set(ref(user,path),{...point,extra:'not allowed'}));
});

test('encrypted private entries are restricted to wrapped-key recipients', async () => {
  const owner=env.authenticatedContext('entry-owner').firestore();
  const recipient=env.authenticatedContext('entry-recipient').firestore();
  const stranger=env.authenticatedContext('entry-stranger').firestore();
  const target=doc(owner,'privateEntries/secure-entry-1');
  const entry={id:'secure-entry-1',ownerUid:'entry-owner',ciphertext:'encrypted-json',
    wrappedKeys:{'entry-owner':'owner-key','entry-recipient':'recipient-key'},
    allowedUsers:['entry-owner','entry-recipient'],createdAt:serverTimestamp(),updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(target,entry));
  await assertSucceeds(getDoc(doc(recipient,'privateEntries/secure-entry-1')));
  await assertFails(getDoc(doc(stranger,'privateEntries/secure-entry-1')));
  await assertFails(updateDoc(doc(recipient,'privateEntries/secure-entry-1'),{ciphertext:'changed'}));
  await assertFails(setDoc(doc(owner,'privateEntries/bad-id'),entry));
  await assertFails(setDoc(doc(owner,'privateEntries/missing-key'),{...entry,id:'missing-key',
    wrappedKeys:{'entry-owner':'owner-key'}}));
  await assertSucceeds(updateDoc(target,{wrappedKeys:{'entry-owner':'owner-key'},
    allowedUsers:['entry-owner'],updatedAt:serverTimestamp()}));
  await assertFails(getDoc(doc(recipient,'privateEntries/secure-entry-1')));
});

test('web entry hashes are owner-only, cannot be listed, and never accept raw tokens', async () => {
  const alice=env.authenticatedContext('entry-alice').firestore();
  const bob=env.authenticatedContext('entry-bob').firestore();
  const anonymous=env.unauthenticatedContext().firestore();
  const value={tokenHash:'a'.repeat(64),updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(doc(alice,'webEntryLinks/entry-alice'),value));
  await assertSucceeds(getDoc(doc(alice,'webEntryLinks/entry-alice')));
  await assertFails(getDoc(doc(bob,'webEntryLinks/entry-alice')));
  await assertFails(getDoc(doc(anonymous,'webEntryLinks/entry-alice')));
  await assertFails(setDoc(doc(bob,'webEntryLinks/entry-alice'),value));
  await assertFails(getDocs(collection(alice,'webEntryLinks')));
  await assertFails(setDoc(doc(alice,'webEntryLinks/entry-alice'),{...value,token:'raw-secret'}));
  await assertFails(setDoc(doc(alice,'webEntryLinks/entry-alice'),{...value,tokenHash:'invalid'}));
  await assertSucceeds(setDoc(doc(alice,'webEntryLinks/entry-alice'),{...value,tokenHash:'b'.repeat(64)}));
  await assertSucceeds(deleteDoc(doc(alice,'webEntryLinks/entry-alice')));
});

test('profile field types lengths and coordinate boundaries are enforced', async () => {
  const owner=env.authenticatedContext('profile-boundary',{email:'profile_bound@users.olive.invalid'}).firestore();
  const target=doc(owner,'users/profile-boundary');
  const base={...profile,username:'profile_bound',address:{line1:'',line2:'',city:'Edmonton',province:'AB',postalCode:'',country:'Canada'}};
  await assertSucceeds(setDoc(target,{...base,address:{...base.address,latitude:90,longitude:180}}));
  await assertFails(setDoc(target,{...base,address:{...base.address,latitude:90.0001,longitude:0}}));
  await assertFails(setDoc(target,{...base,address:{...base.address,latitude:0,longitude:-180.0001}}));
  await assertFails(setDoc(target,{...base,fullName:'x'.repeat(121)}));
  await assertFails(setDoc(target,{...base,language:7}));
  await assertFails(setDoc(target,{...base,situation:'x'.repeat(2001)}));
});

test('directory owners cannot rename or transfer an existing identity', async () => {
  const alice=env.authenticatedContext('directory-a',{email:'directory_a@users.olive.invalid'}).firestore();
  const bob=env.authenticatedContext('directory-b',{email:'directory_b@users.olive.invalid'}).firestore();
  const target=doc(alice,'messageDirectory/directory_a');
  const row={uid:'directory-a',username:'directory_a',publicKey:'public-key',updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(target,row));
  await assertFails(updateDoc(target,{uid:'directory-b'}));
  await assertFails(updateDoc(target,{username:'directory_b'}));
  await assertFails(updateDoc(target,{publicKey:''}));
  await assertFails(updateDoc(doc(bob,'messageDirectory/directory_a'),{publicKey:'stolen'}));
  await assertSucceeds(updateDoc(target,{publicKey:'rotated-key',updatedAt:serverTimestamp()}));
});

test('entry link hashes rotate only with exact lowercase sha256 data', async () => {
  const owner=env.authenticatedContext('entry-owner').firestore();
  const other=env.authenticatedContext('entry-other').firestore();
  const target=doc(owner,'webEntryLinks/entry-owner');
  await assertSucceeds(setDoc(target,{tokenHash:'a'.repeat(64),updatedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(target,{tokenHash:'b'.repeat(64),updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(target,{tokenHash:'B'.repeat(64),updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(target,{tokenHash:'b'.repeat(63),updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(target,{tokenHash:'b'.repeat(64),rawToken:'secret',updatedAt:serverTimestamp()}));
  await assertFails(setDoc(doc(other,'webEntryLinks/entry-owner'),{tokenHash:'c'.repeat(64),updatedAt:serverTimestamp()}));
});

test('private entry recipients and wrapped keys must match exactly', async () => {
  const owner=env.authenticatedContext('private-owner').firestore();
  const target=doc(owner,'privateEntries/private-entry-boundary');
  const base={id:'private-entry-boundary',ownerUid:'private-owner',ciphertext:'encrypted',
    wrappedKeys:{'private-owner':'owner-key'},allowedUsers:['private-owner'],createdAt:1,updatedAt:1};
  await assertSucceeds(setDoc(target,base));
  await assertFails(setDoc(doc(owner,'privateEntries/missing-owner'),{...base,id:'missing-owner',allowedUsers:['other'],wrappedKeys:{other:'key'}}));
  await assertFails(setDoc(doc(owner,'privateEntries/missing-key'),{...base,id:'missing-key',allowedUsers:['private-owner','other']}));
  await assertFails(setDoc(doc(owner,'privateEntries/extra-key'),{...base,id:'extra-key',wrappedKeys:{'private-owner':'key',other:'extra'}}));
  const recipients=Array.from({length:26},(_,index)=>`recipient-${index}`);
  await assertFails(setDoc(doc(owner,'privateEntries/too-many'),{...base,id:'too-many',allowedUsers:['private-owner',...recipients],
    wrappedKeys:Object.fromEntries(['private-owner',...recipients].map(uid=>[uid,'key']))}));
});
